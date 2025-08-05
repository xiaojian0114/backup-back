package com.example.demo.service;

import com.example.demo.controller.ConfigController;
import com.example.demo.entity.BackupTask;
import com.example.demo.entity.Config;
import com.example.demo.entity.HardDisk;
import com.example.demo.repository.BackupTaskRepository;
import com.example.demo.repository.ConfigRepository;
import com.example.demo.repository.HardDiskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.regex.Pattern;

@Service
public class DataClassificationService {
    @Autowired
    private BackupTaskRepository taskRepository;
    @Autowired
    private ConfigRepository configRepository;
    @Autowired
    private BackupService backupService;
    @Autowired
    private HardDiskRepository hardDiskRepository;


    public BackupTask classifyData(String sourcePath) throws Exception {
        Path path = Paths.get(sourcePath);
        // 校验路径是否存在
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("路径不存在：" + sourcePath);
        }

        // 1. 获取文件最后访问时间
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        LocalDateTime fileLastAccessTime = LocalDateTime.ofInstant(
                attrs.lastAccessTime().toInstant(),
                ZoneId.systemDefault()
        );

        // 2. 获取配置的时间阈值（冷数据判断标准）
        Config accessTimeConfig = configRepository.findByConfigKey(ConfigController.LAST_FILE_ACCESS_TIME);
        if (accessTimeConfig == null) {
            throw new RuntimeException("未配置文件访问时间阈值，请先初始化配置");
        }
        LocalDateTime coldDataThreshold = LocalDateTime.parse(
                accessTimeConfig.getConfigValue(),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        );

        // 判断是否为冷数据（文件访问时间早于阈值）
        boolean isColdData = fileLastAccessTime.isBefore(coldDataThreshold);


        // 3. 判断是否为敏感数据
        Config sensitiveConfig = configRepository.findByConfigKey(ConfigController.SENSITIVE_KEYWORDS);
        boolean isSensitive = sensitiveConfig != null && Pattern.compile(sensitiveConfig.getConfigValue())
                .matcher(path.getFileName().toString()).find();


        // 4. 创建并保存备份任务
        BackupTask task = new BackupTask();
        task.setSourcePath(sourcePath);
        task.setSchedule("0 0 0 * * ?"); // 每日凌晨执行
        task.setBackupMode("COPY");
        task.setStatus(isColdData ? "COLD" : "HOT");
        task.setSensitive(isSensitive);
        task.setBackupCount(isSensitive ? 2 : 1); // 敏感文件备份2份
        taskRepository.save(task);


        // 5. 冷数据自动触发备份
        if (isColdData) {
            HardDisk targetDisk = selectAvailableDisk(path.toFile().length() * task.getBackupCount());
            if (targetDisk == null) {
                throw new IllegalStateException("没有可用磁盘满足备份容量要求");
            }
            backupService.executeBackup(task, targetDisk);
        }

        return task;
    }


    // 选择可用磁盘（满足容量要求）
    private HardDisk selectAvailableDisk(long requiredSpace) {
        Config thresholdConfig = configRepository.findByConfigKey(ConfigController.MIGRATION_THRESHOLD);
        long migrationThreshold = thresholdConfig != null ?
                Long.parseLong(thresholdConfig.getConfigValue()) :
                50L * 1024 * 1024 * 1024; // 默认50GB

        // 筛选可用磁盘（状态正常、容量足够）
        for (HardDisk disk : hardDiskRepository.findAll()) {
            if ("ACTIVE".equals(disk.getStatus())
                    && disk.getAvailableCapacity() >= requiredSpace
                    && disk.getAvailableCapacity() >= migrationThreshold) {
                return disk;
            }
        }
        return null;
    }
}