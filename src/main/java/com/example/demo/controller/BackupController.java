package com.example.demo.controller;

import com.example.demo.dto.FileInfoDTO;
import com.example.demo.dto.PathDTO;
import com.example.demo.entity.BackupLog;
import com.example.demo.entity.BackupTask;
import com.example.demo.entity.HardDisk;
import com.example.demo.entity.ResponseResult;
import com.example.demo.service.BackupService;
import com.example.demo.service.DataClassificationService;
import com.example.demo.service.DiskManagementService;
import com.example.demo.service.SearchService;
import com.example.demo.util.CryptoUtil;
import jcifs.smb.SmbException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api")
public class BackupController {
    @Autowired
    private BackupService backupService;
    @Autowired
    private DataClassificationService classificationService;
    @Autowired
    private DiskManagementService diskManagementService;
    @Autowired
    private SearchService searchService;

    private static final Logger log = LoggerFactory.getLogger(BackupController.class);

    @GetMapping("/disks")
    public ResponseResult<List<HardDisk>> getAvailableDisks() {
        List<HardDisk> disks = (List<HardDisk>) backupService.getDiskRepository().findAll();
        return ResponseResult.success(disks, "获取磁盘列表成功");
    }

    @PostMapping("/classify")
    public ResponseResult<BackupTask> classifyData(@RequestBody PathDTO pathDTO) {
        String sourcePath = pathDTO.getSourcePath();
        try {
            BackupTask classifiedTask = classificationService.classifyData(sourcePath);
            return ResponseResult.success(classifiedTask, "数据分类成功");
        } catch (Exception e) {
            log.error("分类失败: sourcePath={}", sourcePath, e);
            return ResponseResult.fail("分类失败：" + e.getMessage());
        }
    }

    private String normalizePath(String path) {
        if (path == null) return "";

        path = path.replace("/", "\\");
        while (path.endsWith("\\") && path.length() > 3) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.length() > 1 && path.charAt(1) == ':') {
            path = path.substring(0, 1).toUpperCase() + path.substring(1);
        }
        return path;
    }
    @PostMapping("/backup")
    @Transactional
    public ResponseResult<Long> startBackup(@RequestBody Map<String, String> request) {
        String sourceDiskId = request.get("sourceDiskId");
        String sourcePath = normalizePath(request.get("sourcePath"));
        String targetDiskId = request.get("targetDiskId");
        String targetPath = normalizePath(request.get("targetPath"));
        String backupMode = request.get("backupMode");

        log.info("收到备份请求: sourcePath={}, targetDiskId={}, targetPath={}, backupMode={}",
                sourcePath, targetDiskId, targetPath, backupMode);

        // 参数校验
        if (sourcePath == null || targetDiskId == null || targetPath == null) {
            return ResponseResult.fail("源路径、目标磁盘或目标路径不能为空");
        }

        File sourceFile = new File(sourcePath);
        if (!sourceFile.exists()) {
            return ResponseResult.fail("源路径不存在: " + sourcePath);
        }

        // 获取目标磁盘
        HardDisk targetDisk = backupService.getDiskRepository().findByDiskId(targetDiskId);
        if (targetDisk == null) {
            return ResponseResult.fail("目标磁盘不存在: " + targetDiskId);
        }

        // 校验目标路径
        Path fullTargetPath = Paths.get(targetDisk.getMountPoint(), targetPath);
        try {
            if (!Files.exists(fullTargetPath)) {
                Path parentDir = fullTargetPath.getParent();
                if (parentDir == null || !Files.isWritable(parentDir)) {
                    throw new SecurityException("无权限在目标位置创建目录");
                }
                Files.createDirectories(fullTargetPath);
                log.info("目标目录创建成功: {}", fullTargetPath);
            } else if (!Files.isDirectory(fullTargetPath)) {
                return ResponseResult.fail("目标路径已存在但不是目录: " + fullTargetPath);
            }
        } catch (Exception e) {
            log.error("目标路径处理失败", e);
            return ResponseResult.fail("目标路径处理失败: " + e.getMessage());
        }

        // 创建并保存任务（核心：返回taskId）
        BackupTask task = new BackupTask();
        task.setSourcePath(sourcePath);
        task.setBackupMode(backupMode != null ? backupMode : "COPY");
        task.setSensitive(false);
        task.setBackupCount(1);
        task.setStatus("PENDING");
        task.setSchedule("");

        // 保存任务获取ID
        BackupTask savedTask = backupService.getTaskRepository().save(task);
        log.info("备份任务已创建: taskId={}", savedTask.getId());

        // 异步执行备份（避免阻塞请求）
        new Thread(() -> {
            try {
                task.setStatus("RUNNING");
                backupService.getTaskRepository().save(task); // 更新状态为运行中
                backupService.executeBackup(task, targetDisk); // 调用服务执行备份
                task.setStatus("COMPLETED");
                backupService.getTaskRepository().save(task); // 完成后更新状态
            } catch (Exception e) {
                log.error("备份任务执行失败: taskId={}", task.getId(), e);
                task.setStatus("FAILED");
                backupService.getTaskRepository().save(task); // 失败后更新状态
            }
        }).start();

        // 返回任务ID给前端
        return ResponseResult.success(savedTask.getId(), "备份任务已启动（taskId=" + savedTask.getId() + "）");
    }

    /**
     * 查询备份进度（基于日志计算）
     */
    @GetMapping("/backup/progress")
    public ResponseResult<Map<String, Object>> getBackupProgress(@RequestParam Long taskId) {
        try {
            // 1. 校验任务是否存在
            BackupTask task = backupService.getTaskRepository().findById(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

            // 2. 查日志计算进度（服务层逻辑已实现通过日志统计）
            List<BackupLog> logs = backupService.getLogRepository().findAllByTaskId(taskId);
            if (logs.isEmpty()) {
                return ResponseResult.success(Map.of("progress", 0.0, "status", task.getStatus()));
            }

            // 3. 计算总大小和已传输大小（复用服务层逻辑）
            long totalSize = 0;
            long transferred = 0;
            for (BackupLog log : logs) {
                File targetFile = new File(log.getTargetPath());
                totalSize += targetFile.exists() ? targetFile.length() : 0;
                transferred += log.getTransferOffset();
            }

            // 4. 计算进度（避免除零）
            double progress = totalSize > 0 ? (transferred * 100.0 / totalSize) : 0;
            Map<String, Object> result = new HashMap<>();
            result.put("progress", Math.min(100.0, progress)); // 进度不超过100%
            result.put("status", task.getStatus()); // 返回任务状态（RUNNING/COMPLETED等）

            return ResponseResult.success(result, "进度查询成功");
        } catch (Exception e) {
            log.error("获取备份进度失败: taskId={}", taskId, e);
            return ResponseResult.fail("获取进度失败: " + e.getMessage());
        }
    }

    @PostMapping("/disk/initialize")
    public ResponseResult<HardDisk> initializeDisk(@RequestBody HardDisk disk) {
        try {
            HardDisk initializedDisk = diskManagementService.initializeDisk(disk.getSerialNumber(), disk.getTotalCapacity());
            return ResponseResult.success(initializedDisk, "磁盘初始化成功");
        } catch (Exception e) {
            log.error("磁盘初始化失败: serialNumber={}", disk.getSerialNumber(), e);
            return ResponseResult.fail("磁盘初始化失败: " + e.getMessage());
        }
    }

    @GetMapping("/disk/check")
    public ResponseResult<String> checkDisks() {
        try {
            diskManagementService.checkAndMigrateDisks();
            return ResponseResult.success("磁盘检查和迁移完成");
        } catch (Exception e) {
            log.error("磁盘检查失败", e);
            return ResponseResult.fail("磁盘检查失败: " + e.getMessage());
        }
    }

    @PostMapping("/disk/migrate")
    public ResponseResult<String> migrateDisk(@RequestBody Map<String, String> request) {
        String sourceDiskId = request.get("sourceDiskId");
        String targetDiskId = request.get("targetDiskId");
        try {
            HardDisk sourceDisk = backupService.getDiskRepository().findByDiskId(sourceDiskId);
            HardDisk targetDisk = backupService.getDiskRepository().findByDiskId(targetDiskId);
            if (sourceDisk == null || targetDisk == null) {
                return ResponseResult.fail("源磁盘或目标磁盘不存在");
            }
            diskManagementService.migrateDisk(sourceDisk, targetDisk);
            return ResponseResult.success("磁盘迁移成功");
        } catch (Exception e) {
            log.error("磁盘迁移失败: sourceDiskId={}, targetDiskId={}", sourceDiskId, targetDiskId, e);
            return ResponseResult.fail("磁盘迁移失败: " + e.getMessage());
        }
    }

    @GetMapping("/search")
    public ResponseResult<List<BackupLog>> searchFiles(@RequestParam String keyword) {
        try {
            List<BackupLog> results = searchService.searchFiles(keyword);
            return ResponseResult.success(results, "搜索完成");
        } catch (Exception e) {
            log.error("搜索失败: keyword={}", keyword, e);
            return ResponseResult.fail("搜索失败: " + e.getMessage());
        }
    }

    @PostMapping("/select-file")
    public ResponseResult<List<FileInfoDTO>> selectFile(@RequestBody Map<String, String> request) {
        try {
            String defaultPath = request.getOrDefault("defaultPath", "");
            if (defaultPath.isEmpty()) {
                return ResponseResult.fail("路径不能为空");
            }

            File dir = new File(defaultPath);
            if (!dir.exists()) {
                return ResponseResult.fail("路径不存在: " + defaultPath);
            }
            if (!dir.isDirectory()) {
                return ResponseResult.fail("路径不是目录: " + defaultPath);
            }

            File[] fileArray = dir.listFiles();
            if (fileArray == null) {
                return ResponseResult.fail("没有权限读取该目录: " + defaultPath);
            }

            List<FileInfoDTO> fileList = Arrays.stream(fileArray)
                    .map(FileInfoDTO::new)
                    .collect(Collectors.toList());

            return ResponseResult.success(fileList, "文件列表获取成功");
        } catch (Exception e) {
            log.error("文件列表获取失败: defaultPath={}", request.getOrDefault("defaultPath", ""), e);
            return ResponseResult.fail("处理失败: " + e.getMessage());
        }
    }


    @GetMapping("/disk/scan")
    public ResponseResult<List<HardDisk>> scanDisks() {
        try {
            List<HardDisk> disks = diskManagementService.scanAndUpdateDisks();
            return ResponseResult.success(disks, "磁盘扫描成功，共发现" + disks.size() + "个磁盘");
        } catch (Exception e) {
            log.error("磁盘扫描失败", e);
            return ResponseResult.fail("扫描失败：" + e.getMessage());
        }
    }

    @PostMapping("/disk/remote/smb")
    public ResponseResult<HardDisk> addSmbDisk(@RequestBody Map<String, String> request) {
        try {
            String smbUrl = request.get("smbUrl");
            String username = request.get("username");
            String password = request.get("password");
            String serialNumber = request.get("serialNumber");

            if (smbUrl == null || username == null || password == null || serialNumber == null) {
                return ResponseResult.fail("缺少必要字段");
            }
            if (!smbUrl.matches("^smb://[\\w\\d\\.-]+/[^/]+$")) {
                return ResponseResult.fail("SMB URL 格式错误，应为 smb://host/shareName");
            }

            String encryptedPwd = CryptoUtil.encrypt(password);
            log.info("Encrypted password: {}", encryptedPwd);
            HardDisk disk = diskManagementService.initializeRemoteSmbDisk(smbUrl, username, encryptedPwd, serialNumber);
            return ResponseResult.success(disk, "SMB远程磁盘初始化成功");
        } catch (SmbException e) {
            log.error("SMB连接失败: {}", e.getMessage(), e);
            return ResponseResult.fail("SMB连接失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("SMB初始化失败: {}", e.getMessage(), e);
            return ResponseResult.fail("初始化失败：" + e.getMessage());
        }
    }

    @PostMapping("/disk/initialize/local")
    public ResponseResult<HardDisk> initializeLocalDisk(@RequestBody HardDisk disk) {
        try {
            HardDisk initializedDisk = diskManagementService.initializeDisk(
                    disk.getSerialNumber(),
                    disk.getTotalCapacity()
            );
            return ResponseResult.success(initializedDisk, "本地磁盘初始化成功");
        } catch (Exception e) {
            log.error("本地磁盘初始化失败: serialNumber={}", disk.getSerialNumber(), e);
            return ResponseResult.fail("本地磁盘初始化失败: " + e.getMessage());
        }
    }

    @PostMapping("/disk/initialize/smb")
    public ResponseResult<HardDisk> initializeSmbDisk(@RequestBody Map<String, String> request) {
        try {
            String smbUrl = request.get("smbUrl");
            String username = request.get("username");
            String password = request.get("password");
            String serialNumber = request.get("serialNumber");

            if (smbUrl == null || username == null || password == null || serialNumber == null) {
                return ResponseResult.fail("缺少必要字段");
            }
            if (!smbUrl.matches("^smb://[\\w\\d\\.-]+/[^/]+$")) {
                return ResponseResult.fail("SMB URL 格式错误，应为 smb://host/shareName");
            }

            String encryptedPwd = CryptoUtil.encrypt(password);
            log.info("Encrypted password: {}", encryptedPwd);
            HardDisk disk = diskManagementService.initializeRemoteSmbDisk(smbUrl, username, encryptedPwd, serialNumber);
            return ResponseResult.success(disk, "SMB远程磁盘初始化成功");
        } catch (Exception e) {
            log.error("SMB远程磁盘初始化失败: {}", e.getMessage(), e);
            return ResponseResult.fail("SMB远程磁盘初始化失败: " + e.getMessage());
        }
    }
}

//2