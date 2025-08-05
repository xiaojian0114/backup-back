package com.example.demo.service;

import com.example.demo.entity.BackupLog;
import com.example.demo.entity.BackupTask;
import com.example.demo.entity.Config;
import com.example.demo.entity.HardDisk;
import com.example.demo.handler.BackupWebSocketHandler;
import com.example.demo.repository.BackupLogRepository;
import com.example.demo.repository.BackupTaskRepository;
import com.example.demo.repository.ConfigRepository;
import com.example.demo.repository.HardDiskRepository;
import com.example.demo.util.ChecksumUtil;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class BackupService {
    @Autowired
    private BackupTaskRepository taskRepository;
    @Autowired
    private BackupLogRepository logRepository;
    @Autowired
    private HardDiskRepository diskRepository;
    @Autowired
    private ConfigRepository configRepository;
    @Autowired
    private BackupWebSocketHandler webSocketHandler;

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final long PROGRESS_PUSH_THRESHOLD = 100 * 1024 * 1024; // 每100MB推送进度
    private static final long PAUSE_CHECK_INTERVAL = 1000; // 每秒检查暂停状态
    private static final long COLD_DATA_THRESHOLD_DAYS = 365; // 冷数据阈值：365天

    private long getMigrationThreshold() {
        Config thresholdConfig = configRepository.findByConfigKey("migration_threshold");
        return thresholdConfig != null ? Long.parseLong(thresholdConfig.getConfigValue()) : 50L * 1024 * 1024 * 1024;
    }

    public void executeBackup(BackupTask task, HardDisk targetDisk) throws Exception {
        log("开始执行备份任务，源路径: " + task.getSourcePath());
        File sourceFile = new File(task.getSourcePath());
        preCheckSourceFile(sourceFile);

        task.setStatus("RUNNING");
        task.setPaused(false);
        task.setTargetDiskId(targetDisk.getDiskId());
        long totalSize = sourceFile.isDirectory() ? calculateFolderSize(sourceFile) : sourceFile.length();
        task.setTotalSize(totalSize); // 初始总大小，稍后根据分类调整
        task.setCompletedSize(0L);
        taskRepository.save(task);

        Config sensitiveConfig = configRepository.findByConfigKey("sensitive_keywords");
        String sensitivePattern = sensitiveConfig != null ? sensitiveConfig.getConfigValue() : null;

        List<String> failedFiles = new ArrayList<>();
        long actualUsedSpace = 0;

        if (sourceFile.isDirectory()) {
            actualUsedSpace = backupFolder(sourceFile, task, targetDisk, sensitivePattern, failedFiles);
        } else {
            // 对单个文件进行分类
            int backupCount = classifyFile(sourceFile, sensitivePattern);
            task.setBackupCount(backupCount);
            task.setTotalSize(totalSize * backupCount);
            taskRepository.save(task);

            if (backupCount == 0) {
                log("文件无需备份: " + sourceFile.getName());
                task.setStatus("COMPLETED");
                task.setCompletedSize(0L);
                taskRepository.save(task);
                return;
            }

            // 检查目标磁盘空间
            long requiredSpace = task.getTotalSize();
            if (targetDisk.getAvailableCapacity() < requiredSpace || targetDisk.getAvailableCapacity() < getMigrationThreshold()) {
                task.setStatus("FAILED");
                taskRepository.save(task);
                throw new IllegalStateException("目标磁盘容量不足");
            }

            String checksum = ChecksumUtil.calculateSHA256(sourceFile);
            if (backupCount == 1 && logRepository.findByChecksum(checksum) != null) {
                log("文件已备份，跳过: " + sourceFile.getName());
                task.setStatus("COMPLETED");
                task.setCompletedSize(totalSize);
                taskRepository.save(task);
                return;
            }

            // 执行文件备份
            try {
                for (int i = 1; i <= backupCount; i++) {
                    if (task.isPaused()) {
                        handlePause(task);
                        return;
                    }
                    String targetPath = String.format("%s%s%s%s",
                            targetDisk.getMountPoint(),
                            File.separator,
                            sourceFile.getName(),
                            i > 1 ? "_copy" + i : "");
                    actualUsedSpace += backupFile(sourceFile, targetPath, task, targetDisk, checksum, i);
                    task.setCompletedSize(task.getCompletedSize() + sourceFile.length());
                    taskRepository.save(task);
                }
            } catch (Exception e) {
                failedFiles.add(sourceFile.getAbsolutePath() + ": " + e.getMessage());
            }
        }

        if (!failedFiles.isEmpty()) {
            task.setStatus("PARTIALLY_FAILED");
            taskRepository.save(task);
            throw new Exception("部分文件备份失败: " + String.join(", ", failedFiles));
        }

        if (actualUsedSpace > 0) {
            targetDisk.setAvailableCapacity(targetDisk.getAvailableCapacity() - actualUsedSpace);
            diskRepository.save(targetDisk);
            generateIndex(task.getId());
        }

        task.setStatus("COMPLETED");
        task.setCompletedSize(task.getTotalSize());
        taskRepository.save(task);
    }

    private int classifyFile(File file, String sensitivePattern) throws IOException {
        boolean isSensitive = sensitivePattern != null && Pattern.compile(sensitivePattern).matcher(file.getName()).find();
        if (isSensitive) {
            log("文件包含敏感词: " + file.getName());
            return 2; // 敏感文件备份两次
        }

        BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        LocalDateTime lastModified = LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), java.time.ZoneId.systemDefault());
        long daysSinceModified = ChronoUnit.DAYS.between(lastModified, LocalDateTime.now());
        if (daysSinceModified > COLD_DATA_THRESHOLD_DAYS) {
            log("文件为冷数据: " + file.getName() + "，上次修改于: " + lastModified);
            return 1; // 冷数据备份一次
        }

        log("文件无需备份: " + file.getName());
        return 0; // 其他情况不备份
    }

    private long backupFolder(File sourceFolder, BackupTask task, HardDisk disk, String sensitivePattern, List<String> failedFiles) throws Exception {
        Path sourcePath = sourceFolder.toPath();
        Path targetBasePath = Paths.get(disk.getMountPoint(), sourceFolder.getName());
        log("【文件夹备份】源文件夹: " + sourcePath.toAbsolutePath() + "，目标根路径: " + targetBasePath.toAbsolutePath());

        long actualUsedSpace = 0;
        Files.createDirectories(targetBasePath);
        List<Path> paths = Files.walk(sourcePath).collect(Collectors.toList());

        // 计算总备份大小并设置备份次数
        long totalSize = 0;
        Map<Path, Integer> fileBackupCounts = new HashMap<>();
        for (Path source : paths) {
            if (Files.isRegularFile(source)) {
                int backupCount = classifyFile(source.toFile(), sensitivePattern);
                fileBackupCounts.put(source, backupCount);
                totalSize += source.toFile().length() * backupCount;
            }
        }
        task.setTotalSize(totalSize);
        task.setBackupCount(fileBackupCounts.values().stream().mapToInt(Integer::intValue).sum());
        taskRepository.save(task);

        // 检查目标磁盘空间
        if (disk.getAvailableCapacity() < totalSize || disk.getAvailableCapacity() < getMigrationThreshold()) {
            task.setStatus("FAILED");
            taskRepository.save(task);
            throw new IllegalStateException("目标磁盘容量不足");
        }

        // 备份文件夹内容
        for (Path source : paths) {
            if (task.isPaused()) {
                handlePause(task);
                return actualUsedSpace;
            }
            try {
                Path target = targetBasePath.resolve(sourcePath.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                    log("【文件夹创建】已创建目录: " + target.toAbsolutePath());
                } else {
                    int backupCount = fileBackupCounts.getOrDefault(source, 0);
                    if (backupCount == 0) {
                        log("【跳过备份】文件: " + source.getFileName());
                        continue;
                    }
                    String fileChecksum = ChecksumUtil.calculateSHA256(source.toFile());
                    if (backupCount == 1 && logRepository.findByChecksum(fileChecksum) != null) {
                        log("【去重跳过】文件已备份，跳过: " + source.getFileName() + "，校验和: " + fileChecksum);
                        continue;
                    }
                    for (int i = 1; i <= backupCount; i++) {
                        String targetPath = backupCount > 1 && i > 1 ?
                                target.toString() + "_copy" + i : target.toString();
                        actualUsedSpace += backupFile(source.toFile(), targetPath, task, disk, fileChecksum, i);
                        task.setCompletedSize(task.getCompletedSize() + source.toFile().length());
                        taskRepository.save(task);
                    }
                }
            } catch (Exception e) {
                String errorMsg = "文件夹备份失败，文件: " + source.toAbsolutePath() + "，错误: " + e.getMessage();
                logError("【文件夹备份失败】" + errorMsg);
                failedFiles.add(errorMsg);
                pushFailureBreakpoint(source.toFile(), task, disk, e.getMessage());
            }
        }
        return actualUsedSpace;
    }

    private long backupFile(File sourceFile, String targetPath, BackupTask task, HardDisk disk, String checksum, int copyIndex) throws Exception {
        if (sourceFile.isDirectory()) {
            throw new IllegalArgumentException("backupFile 不支持文件夹: " + sourceFile.getAbsolutePath());
        }

        BackupLog log = new BackupLog();
        log.setTaskId(task.getId());
        log.setFilename(sourceFile.getName());
        log.setDiskId(disk.getId());
        log.setTargetPath(targetPath);
        log.setChecksum(checksum);
        log.setStatus("RUNNING");
        log.setTransferOffset(0L);
        log.setBackupTime(LocalDateTime.now().toString());
        logRepository.save(log);

        Path targetDir = Paths.get(targetPath).getParent();
        Files.createDirectories(targetDir);

        long totalRead = 0;
        long lastPushedOffset = 0;
        try (RandomAccessFile source = new RandomAccessFile(sourceFile, "r");
             RandomAccessFile target = new RandomAccessFile(targetPath, "rw")) {
            source.seek(log.getTransferOffset());
            target.seek(log.getTransferOffset());
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = source.read(buffer)) != -1) {
                if (task.isPaused()) {
                    log.setStatus("PAUSED");
                    log.setBackupTime(LocalDateTime.now().toString());
                    logRepository.save(log);
                    webSocketHandler.sendBreakpoint(log);
                    log("【备份暂停】文件: " + targetPath + "，断点: " + log.getTransferOffset());
                    return totalRead;
                }

                target.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
                log.setTransferOffset(log.getTransferOffset() + bytesRead);

                if (log.getTransferOffset() - lastPushedOffset >= PROGRESS_PUSH_THRESHOLD) {
                    logRepository.save(log);
                    webSocketHandler.sendBreakpoint(log);
                    lastPushedOffset = log.getTransferOffset();
                    log("【备份进度】日志ID: " + log.getId() + "，已传输: " + totalRead / (1024 * 1024) + " MB");
                }

                Thread.sleep(10); // 模拟暂停检查
            }

            logRepository.save(log);
            webSocketHandler.sendBreakpoint(log);

            String targetChecksum = ChecksumUtil.calculateSHA256(new File(targetPath));
            if (!checksum.equals(targetChecksum)) {
                log.setStatus("FAILED");
                logRepository.save(log);
                webSocketHandler.sendBreakpoint(log);
                throw new Exception("校验和不匹配");
            }

            if (!IS_WINDOWS && targetPath.endsWith(".zip")) {
                Set<PosixFilePermission> perms = new HashSet<>();
                perms.add(PosixFilePermission.OWNER_READ);
                perms.add(PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(Paths.get(targetPath), perms);
            }

            if ("MOVE".equals(task.getBackupMode())) {
                Files.deleteIfExists(sourceFile.toPath());
            }

            log.setStatus("SUCCESS");
            logRepository.save(log);
            webSocketHandler.sendBreakpoint(log);
            return totalRead;
        } catch (Exception e) {
            log.setStatus("INTERRUPTED");
            log.setBackupTime(LocalDateTime.now().toString());
            logRepository.save(log);
            webSocketHandler.sendBreakpoint(log);
            throw e;
        }
    }

    public float getProgress(Long taskId) {
        BackupTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        if (task.getTotalSize() == 0) return 0;
        return (float) task.getCompletedSize() / task.getTotalSize() * 100;
    }

    private void handlePause(BackupTask task) throws Exception {
        task.setStatus("PAUSED");
        taskRepository.save(task);
        log("【任务暂停】任务ID: " + task.getId());
        while (task.isPaused()) {
            Thread.sleep(PAUSE_CHECK_INTERVAL);
            task = taskRepository.findById(task.getId()).orElseThrow(() -> new IllegalStateException("任务不存在"));
            if (task.getStatus().equals("CANCELED")) {
                cancelBackup(task);
                throw new Exception("备份任务已取消");
            }
        }
        task.setStatus("RUNNING");
        taskRepository.save(task);
        log("【任务继续】任务ID: " + task.getId());
    }

    public void cancelBackup(BackupTask task) throws Exception {
        task.setStatus("CANCELED");
        task.setPaused(false);
        taskRepository.save(task);
        log("【任务取消】任务ID: " + task.getId());

        List<BackupLog> logs = logRepository.findAllByTaskId(task.getId());
        for (BackupLog log : logs) {
            try {
                File targetFile = new File(log.getTargetPath());
                if (targetFile.exists()) {
                    Files.deleteIfExists(targetFile.toPath());
                    log("【文件删除】已删除目标文件: " + log.getTargetPath());
                }
                log.setStatus("CANCELED");
                logRepository.save(log);
                webSocketHandler.sendBreakpoint(log);
            } catch (Exception e) {
                logError("【文件删除失败】目标文件: " + log.getTargetPath() + "，错误: " + e.getMessage());
            }
        }
    }

    public void pauseBackup(Long taskId) throws Exception {
        BackupTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        if (!task.getStatus().equals("RUNNING")) {
            throw new IllegalStateException("任务不在运行状态，无法暂停");
        }
        task.setPaused(true);
        task.setStatus("PAUSED");
        taskRepository.save(task);
        log("【暂停请求】任务ID: " + taskId);
    }

    public void resumeBackup(Long taskId) throws Exception {
        BackupTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        if (!task.getStatus().equals("PAUSED")) {
            throw new IllegalStateException("任务不在暂停状态，无法继续");
        }
        task.setPaused(false);
        taskRepository.save(task);
        log("【继续请求】任务ID: " + taskId);

        HardDisk targetDisk = diskRepository.findByDiskId(task.getTargetDiskId());
        if (targetDisk == null) {
            throw new IllegalStateException("目标磁盘不存在");
        }
        new Thread(() -> {
            try {
                executeBackup(task, targetDisk);
            } catch (Exception e) {
                logError("继续备份失败: " + e.getMessage());
                task.setStatus("FAILED");
                taskRepository.save(task);
            }
        }).start();
    }

    private long calculateFolderSize(File folder) {
        long size = 0;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                size += file.isDirectory() ? calculateFolderSize(file) : file.length();
            }
        } else {
            logError("【文件夹大小计算失败】无法列出目录内容: " + folder.getAbsolutePath());
        }
        return size;
    }

    private void preCheckSourceFile(File sourceFile) throws Exception {
        if (!sourceFile.exists()) {
            throw new IllegalArgumentException("源路径不存在: " + sourceFile.getAbsolutePath());
        }
        if (!sourceFile.canRead()) {
            throw new SecurityException("源文件不可读: " + sourceFile.getAbsolutePath());
        }
        if (sourceFile.isDirectory() && !sourceFile.canExecute()) {
            throw new SecurityException("源目录不可访问: " + sourceFile.getAbsolutePath());
        }
    }

    private void generateIndex(Long taskId) throws Exception {
        Iterable<BackupLog> logs = logRepository.findAllByTaskId(taskId);
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Backup Index");

        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("ID");
        header.createCell(1).setCellValue("Filename");
        header.createCell(2).setCellValue("Disk ID");
        header.createCell(3).setCellValue("Target Path");
        header.createCell(4).setCellValue("Backup Time");
        header.createCell(5).setCellValue("Checksum");
        header.createCell(6).setCellValue("Status");
        header.createCell(7).setCellValue("Transfer Offset");

        int rowNum = 1;
        for (BackupLog log : logs) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(log.getId());
            row.createCell(1).setCellValue(log.getFilename());
            row.createCell(2).setCellValue(log.getDiskId());
            row.createCell(3).setCellValue(log.getTargetPath());
            row.createCell(4).setCellValue(log.getBackupTime());
            row.createCell(5).setCellValue(log.getChecksum());
            row.createCell(6).setCellValue(log.getStatus());
            row.createCell(7).setCellValue(log.getTransferOffset() != null ? log.getTransferOffset() : 0);
        }

        String userHome = System.getProperty("user.home");
        String indexDir = userHome + File.separator + "backup" + File.separator + "indexes";
        Path indexDirPath = Paths.get(indexDir);
        Files.createDirectories(indexDirPath);
        String indexPath = indexDir + File.separator + "index_" + taskId + ".xlsx";

        try (FileOutputStream fos = new FileOutputStream(indexPath)) {
            workbook.write(fos);
        }
        workbook.close();
        log("【索引生成】已保存索引文件到: " + indexPath);

        for (BackupLog log : logs) {
            log.setIndexPath(indexPath);
            logRepository.save(log);
        }

        if (logs.iterator().hasNext()) {
            Path targetIndexPath = Paths.get(logs.iterator().next().getTargetPath())
                    .getParent()
                    .resolve("indexes")
                    .resolve("index_" + taskId + ".xlsx");
            Files.createDirectories(targetIndexPath.getParent());
            Files.copy(Paths.get(indexPath), targetIndexPath, StandardCopyOption.REPLACE_EXISTING);
            log("【索引同步】已同步索引到目标盘: " + targetIndexPath);
        }
    }

    private void pushFailureBreakpoint(File sourceFile, BackupTask task, HardDisk disk, String errorMsg) {
        try {
            BackupLog failureLog = new BackupLog();
            failureLog.setTaskId(task.getId());
            failureLog.setFilename(sourceFile.getName());
            failureLog.setDiskId(disk.getId());
            failureLog.setTargetPath(sourceFile.getAbsolutePath());
            failureLog.setStatus("FAILED");
            failureLog.setTransferOffset(0L);
            failureLog.setBackupTime(LocalDateTime.now().toString());
            failureLog.setChecksum("");

            webSocketHandler.sendBreakpoint(failureLog);
            log("【失败断点推送】已推送失败信息: " + sourceFile.getName() + "，错误: " + errorMsg);
        } catch (Exception e) {
            logError("【失败断点推送失败】" + e.getMessage());
        }
    }

    public BackupLogRepository getLogRepository() {
        return logRepository;
    }

    public HardDiskRepository getDiskRepository() {
        return diskRepository;
    }

    public BackupTaskRepository getTaskRepository() {
        return taskRepository;
    }

    private void log(String message) {
        System.out.println("[" + LocalDateTime.now() + "] " + message);
    }

    private void logError(String message) {
        System.err.println("[" + LocalDateTime.now() + "] ERROR: " + message);
    }
}