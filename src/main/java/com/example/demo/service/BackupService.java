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
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDateTime;
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
    private BackupWebSocketHandler webSocketHandler;  // 注入WebSocket处理器

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final long PROGRESS_PUSH_THRESHOLD = 100 * 1024 * 1024;  // 每100MB推送一次进度

    private long getMigrationThreshold() {
        Config thresholdConfig = configRepository.findByConfigKey("migration_threshold");
        return thresholdConfig != null ? Long.parseLong(thresholdConfig.getConfigValue()) : 50L * 1024 * 1024 * 1024; // 默认 50GB
    }

    public void executeBackup(BackupTask task, HardDisk targetDisk) throws Exception {
        log("开始执行备份任务，源路径: " + task.getSourcePath());
        log("目标盘挂载点: " + targetDisk.getMountPoint() + "，目标盘ID: " + targetDisk.getId());

        File sourceFile = new File(task.getSourcePath());
        preCheckSourceFile(sourceFile);

        // 保存任务
        task = taskRepository.save(task);
        log("【数据库操作】任务保存成功, ID: " + task.getId());
        // 标记任务为运行中
        task.setStatus("RUNNING");
        taskRepository.save(task);

        // 检查是否为案件文件夹
        boolean isCaseFolder = isCaseFolder(sourceFile);
        log("【文件夹类型检查】" + sourceFile.getAbsolutePath() + " 是否为案件文件夹: " + isCaseFolder);

        // 检查敏感关键词
        Config sensitiveConfig = configRepository.findByConfigKey("sensitive_keywords");
        boolean isSensitive = sensitiveConfig != null && Pattern.compile(sensitiveConfig.getConfigValue()).matcher(sourceFile.getName()).find();
        log("【敏感文件检查】关键词配置: " + (sensitiveConfig != null ? sensitiveConfig.getConfigValue() : "未配置") + "，是否敏感: " + isSensitive);
        if (isSensitive) {
            task.setSensitive(true);
            task.setBackupCount(2);
            task = taskRepository.save(task);
            log("【敏感文件处理】已更新任务为敏感文件，备份次数调整为: " + task.getBackupCount());
        }

        // 验证目标磁盘容量
        long requiredSpace = sourceFile.isDirectory() ? calculateFolderSize(sourceFile) : sourceFile.length();
        requiredSpace *= task.getBackupCount();
        log("源文件大小: " + requiredSpace + " bytes，需备份次数: " + task.getBackupCount() + "，所需空间: " + requiredSpace + " bytes");
        if (targetDisk.getAvailableCapacity() < requiredSpace || targetDisk.getAvailableCapacity() < getMigrationThreshold()) {
            String errorMsg = String.format("目标磁盘容量不足（可用: %d bytes，所需: %d bytes，阈值: %d bytes）",
                    targetDisk.getAvailableCapacity(), requiredSpace, getMigrationThreshold());
            logError("【容量检查失败】" + errorMsg);
            // 更新任务状态并推送中断信息
            task.setStatus("FAILED");
            taskRepository.save(task);
            throw new IllegalStateException(errorMsg);
        }

        // 去重检查（仅对单文件）
        String checksum = null;
        if (!sourceFile.isDirectory()) {
            try {
                checksum = ChecksumUtil.calculateSHA256(sourceFile);
                log("【去重检查】源文件SHA256: " + checksum);
                if (!isSensitive && logRepository.findByChecksum(checksum) != null) {
                    log("【去重跳过】文件已备份，跳过: " + sourceFile.getName());
                    task.setStatus("COMPLETED");
                    taskRepository.save(task);
                    return;
                }
            } catch (Exception e) {
                logError("【去重检查失败】文件: " + sourceFile.getAbsolutePath() + "，错误: " + e.getMessage());
                task.setStatus("FAILED");
                taskRepository.save(task);
                throw new Exception("去重检查失败: " + e.getMessage(), e);
            }
        }

        // 执行备份
        List<String> failedFiles = new ArrayList<>();
        long actualUsedSpace = 0;
        try {
            if (sourceFile.isDirectory()) {
                log("【备份执行】开始备份文件夹: " + sourceFile.getAbsolutePath());
                actualUsedSpace = backupFolder(sourceFile, task, targetDisk, isCaseFolder, failedFiles);
            } else {
                log("【备份执行】开始备份单文件: " + sourceFile.getAbsolutePath() + "，共备份 " + task.getBackupCount() + " 次");
                for (int i = 1; i <= task.getBackupCount(); i++) {
                    String targetPath = String.format("%s%s%s%s",
                            targetDisk.getMountPoint(),
                            File.separator,
                            sourceFile.getName(),
                            i > 1 ? "_copy" + i : "");
                    log("【备份执行】第 " + i + " 次备份目标路径: " + targetPath);
                    try {
                        actualUsedSpace += backupFile(sourceFile, targetPath, task, targetDisk, checksum, i);
                    } catch (Exception e) {
                        failedFiles.add(sourceFile.getAbsolutePath() + " (错误: " + e.getMessage() + ")");
                        // 推送单个文件备份失败的断点信息
                        pushFailureBreakpoint(sourceFile, task, targetDisk, e.getMessage());
                    }
                }
            }

            // 检查备份是否完全成功
            if (!failedFiles.isEmpty()) {
                String errorMsg = "部分文件备份失败: " + String.join(", ", failedFiles);
                logError("【备份失败】" + errorMsg);
                task.setStatus("PARTIALLY_FAILED");
                taskRepository.save(task);
                throw new Exception(errorMsg);
            }

            // 更新硬盘可用容量
            targetDisk.setAvailableCapacity(targetDisk.getAvailableCapacity() - actualUsedSpace);
            diskRepository.save(targetDisk);
            log("【磁盘更新】目标磁盘可用容量更新: " + targetDisk.getAvailableCapacity() + " bytes（已使用: " + actualUsedSpace + " bytes）");

            // 生成索引
            generateIndex(task.getId());

            // 标记任务完成
            task.setStatus("COMPLETED");
            taskRepository.save(task);
            log("【备份完成】任务ID: " + task.getId() + " 全部备份成功");

        } catch (Exception e) {
            // 全局异常处理，推送最终断点
            task.setStatus("FAILED");
            taskRepository.save(task);
            logError("【备份任务全局异常】" + e.getMessage());
            throw e;
        }
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

    private long backupFolder(File sourceFolder, BackupTask task, HardDisk disk, boolean isCaseFolder, List<String> failedFiles) throws Exception {
        Path sourcePath = sourceFolder.toPath();
        Path targetBasePath = Paths.get(disk.getMountPoint(), sourceFolder.getName());
        log("【文件夹备份】源文件夹: " + sourcePath.toAbsolutePath() + "，目标根路径: " + targetBasePath.toAbsolutePath());

        long actualUsedSpace = 0;
        Files.createDirectories(targetBasePath);
        List<Path> paths = Files.walk(sourcePath).collect(Collectors.toList());
        for (Path source : paths) {
            try {
                Path target = targetBasePath.resolve(sourcePath.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                    log("【文件夹创建】已创建目录: " + target.toAbsolutePath());
                } else {
                    String fileChecksum = ChecksumUtil.calculateSHA256(source.toFile());
                    if (!task.getSensitive() && logRepository.findByChecksum(fileChecksum) != null) {
                        log("【去重跳过】文件已备份，跳过: " + source.getFileName() + "，校验和: " + fileChecksum);
                        continue;
                    }
                    actualUsedSpace += backupFile(source.toFile(), target.toString(), task, disk, fileChecksum, 1);
                }
            } catch (Exception e) {
                String errorMsg = "文件夹备份失败，文件: " + source.toAbsolutePath() + "，错误: " + e.getMessage();
                logError("【文件夹备份失败】" + errorMsg);
                failedFiles.add(errorMsg);
                // 推送文件夹内文件备份失败的断点信息
                pushFailureBreakpoint(source.toFile(), task, disk, e.getMessage());
            }
        }

        // 敏感文件额外备份
        if (task.getSensitive()) {
            Path copyPath = Paths.get(disk.getMountPoint(), sourceFolder.getName() + "_copy2");
            log("【敏感文件备份】开始额外备份到: " + copyPath.toAbsolutePath());
            Files.createDirectories(copyPath);
            for (Path source : paths) {
                try {
                    Path target = copyPath.resolve(sourcePath.relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                        log("【文件夹创建】已创建目录: " + target.toAbsolutePath());
                    } else {
                        String fileChecksum = ChecksumUtil.calculateSHA256(source.toFile());
                        // 敏感文件强制备份，跳过去重检查
                        actualUsedSpace += backupFile(source.toFile(), target.toString(), task, disk, fileChecksum, 2);
                    }
                } catch (Exception e) {
                    String errorMsg = "敏感文件额外备份失败，文件: " + source.toAbsolutePath() + "，错误: " + e.getMessage();
                    logError("【敏感备份失败】" + errorMsg);
                    failedFiles.add(errorMsg);
                    pushFailureBreakpoint(source.toFile(), task, disk, e.getMessage());
                }
            }
        }
        return actualUsedSpace;
    }

    private void preCheckSourceFile(File sourceFile) throws Exception {
        log("【检查】源路径: " + sourceFile.getAbsolutePath());
        log("【检查】是否存在: " + sourceFile.exists());
        log("【检查】是否可读: " + sourceFile.canRead());
        log("【检查】是否是目录: " + sourceFile.isDirectory());
        log("【检查】是否可执行: " + sourceFile.canExecute());

        if (!sourceFile.exists()) {
            String errorMsg = "源路径不存在: " + sourceFile.getAbsolutePath();
            logError("【路径检查失败】" + errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }

        if (!sourceFile.canRead()) {
            String errorMsg = "源文件不可读（无权限）: " + sourceFile.getAbsolutePath() + "，请确认文件权限或以管理员身份运行";
            logError("【权限检查失败】" + errorMsg);
            throw new SecurityException(errorMsg);
        }

        if (sourceFile.isDirectory() && !sourceFile.canExecute()) {
            String errorMsg = "源目录不可访问（无权限列出内容）: " + sourceFile.getAbsolutePath();
            logError("【权限检查失败】" + errorMsg);
            throw new SecurityException(errorMsg);
        }

        if (sourceFile.isDirectory()) {
            try {
                File[] files = sourceFile.listFiles();
                log("【目录内容检查】目录包含文件数: " + (files != null ? files.length : "无法列出"));
                if (files == null) {
                    String errorMsg = "无法列出目录内容，可能无权限或目录损坏: " + sourceFile.getAbsolutePath();
                    logError("【目录检查失败】" + errorMsg);
                    throw new IOException(errorMsg);
                }
            } catch (Exception e) {
                String errorMsg = "检查目录内容失败: " + sourceFile.getAbsolutePath() + "，错误: " + e.getMessage();
                logError("【目录检查失败】" + errorMsg);
                throw new IOException(errorMsg, e);
            }
        } else {
            try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "r")) {
                log("【文件占用检查】源文件可正常打开，未被占用: " + sourceFile.getAbsolutePath());
            } catch (IOException e) {
                String errorMsg = "源文件被占用或无法打开: " + sourceFile.getAbsolutePath() + "，错误: " + e.getMessage();
                logError("【文件占用检查失败】" + errorMsg);
                throw new IOException(errorMsg, e);
            }
        }
    }

    private boolean isCaseFolder(File file) {
        if (!file.isDirectory()) {
            return false;
        }
        String[] caseSubfolders = {"镜像", "录屏", "截图", "数据报告"};
        for (String subfolder : caseSubfolders) {
            File subDir = new File(file, subfolder);
            if (subDir.exists() && subDir.isDirectory()) {
                log("【案件文件夹判断】匹配子目录: " + subDir.getAbsolutePath());
                return true;
            }
        }
        return false;
    }

    private long backupFile(File sourceFile, String targetPath, BackupTask task, HardDisk disk, String checksum, int copyIndex) throws Exception {
        if (sourceFile.isDirectory()) {
            String errorMsg = "backupFile 不支持文件夹: " + sourceFile.getAbsolutePath();
            logError("【备份失败】" + errorMsg);
            throw new IllegalArgumentException(errorMsg);
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
        log("【备份日志】创建日志记录: taskId=" + task.getId() + ", filename=" + sourceFile.getName() + ", targetPath=" + targetPath);

        // 保存日志
        try {
            log = logRepository.save(log);
            log("【备份日志】已保存日志记录，日志ID: " + log.getId() + "，目标路径: " + targetPath);
        } catch (Exception e) {
            String errorMsg = "保存备份日志失败: " + sourceFile.getAbsolutePath() + "，错误: " + e.getMessage();
            logError("【备份日志失败】" + errorMsg);
            throw new Exception(errorMsg, e);
        }

        try {
            Path targetDir = Paths.get(targetPath).getParent();
            Files.createDirectories(targetDir);
            log("【目录创建】已确保目标目录存在: " + targetDir.toAbsolutePath());

            long totalRead = 0;
            long lastPushedOffset = 0;
            try (RandomAccessFile source = new RandomAccessFile(sourceFile, "r");
                 RandomAccessFile target = new RandomAccessFile(targetPath, "rw")) {
                source.seek(log.getTransferOffset());
                target.seek(log.getTransferOffset());
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = source.read(buffer)) != -1) {
                    target.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    log.setTransferOffset(log.getTransferOffset() + bytesRead);

                    // 达到推送阈值时发送进度
                    if (log.getTransferOffset() - lastPushedOffset >= PROGRESS_PUSH_THRESHOLD) {
                        logRepository.save(log);
                        webSocketHandler.sendBreakpoint(log);  // 推送进度断点
                        lastPushedOffset = log.getTransferOffset();
                        log("【备份进度】日志ID: " + log.getId() + "，已传输: " + totalRead / (1024 * 1024) + " MB");
                    }
                }
                // 最后一次推送完整进度
                logRepository.save(log);
                webSocketHandler.sendBreakpoint(log);  // 推送完成断点
                log("【备份进度】日志ID: " + log.getId() + "，传输完成，总大小: " + totalRead + " bytes");
            }

            String targetChecksum = ChecksumUtil.calculateSHA256(new File(targetPath));
            if (!checksum.equals(targetChecksum)) {
                log.setStatus("FAILED");
                logRepository.save(log);
                webSocketHandler.sendBreakpoint(log);  // 推送校验失败断点
                String errorMsg = "校验和不匹配，源文件: " + checksum + "，目标文件: " + targetChecksum + "，路径: " + targetPath;
                logError("【校验失败】" + errorMsg);
                throw new Exception(errorMsg);
            }
            log("【校验成功】目标文件校验和匹配: " + targetPath);

            if (!IS_WINDOWS && targetPath.endsWith(".zip")) {
                Set<PosixFilePermission> perms = new HashSet<>();
                perms.add(PosixFilePermission.OWNER_READ);
                perms.add(PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(Paths.get(targetPath), perms);
                log("【权限设置】已为ZIP文件设置Posix权限: " + targetPath);
            }

            if ("MOVE".equals(task.getBackupMode())) {
                Files.deleteIfExists(sourceFile.toPath());
                log("【MOVE模式】已删除源文件: " + sourceFile.getAbsolutePath());
            }

            log.setStatus("SUCCESS");
            logRepository.save(log);
            webSocketHandler.sendBreakpoint(log);  // 推送成功断点
            log("【备份成功】日志ID: " + log.getId() + "，文件: " + targetPath);
            return totalRead;
        } catch (Exception e) {
            // 备份中断时记录断点并推送
            log.setStatus("INTERRUPTED");
            log.setBackupTime(LocalDateTime.now().toString());
            logRepository.save(log);
            webSocketHandler.sendBreakpoint(log);  // 推送中断断点

            String errorMsg = "备份文件失败，目标路径: " + targetPath + "，错误: " + e.getMessage();
            logError("【备份失败】" + errorMsg);
            throw new Exception(errorMsg, e);
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

    /**
     * 推送失败的断点信息
     */
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

    private void log(String message) {
        System.out.println("[" + LocalDateTime.now() + "] " + message);
    }

    private void logError(String message) {
        System.err.println("[" + LocalDateTime.now() + "] ERROR: " + message);
    }

    public BackupTaskRepository getTaskRepository() {
        return taskRepository;
    }
}

//3