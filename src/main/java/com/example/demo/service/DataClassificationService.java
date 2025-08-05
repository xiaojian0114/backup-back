package com.example.demo.service;

import com.example.demo.controller.ConfigController;
import com.example.demo.entity.BackupTask;
import com.example.demo.entity.Config;
import com.example.demo.repository.BackupTaskRepository;
import com.example.demo.repository.ConfigRepository;
import com.example.demo.repository.HardDiskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final long COLD_DATA_THRESHOLD_DAYS = 365;

    public Map<String, Object> classifyData(String sourcePath) throws Exception {
        Path path = Paths.get(sourcePath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("路径不存在：" + sourcePath);
        }

        // 获取敏感词配置
        Config sensitiveConfig = configRepository.findByConfigKey(ConfigController.SENSITIVE_KEYWORDS);
        String sensitivePattern = sensitiveConfig != null ? sensitiveConfig.getConfigValue() : null;
        if (sensitivePattern == null || sensitivePattern.trim().isEmpty()) {
            throw new IllegalStateException("敏感词配置未找到或为空，请检查 config 表中的 " + ConfigController.SENSITIVE_KEYWORDS);
        }

        // 创建备份任务
        BackupTask task = new BackupTask();
        task.setSourcePath(sourcePath);
        task.setSchedule(""); // 不设置自动调度
        task.setBackupMode("COPY");
        task.setStatus("PENDING");
        task.setSensitive(false); // 默认非敏感，具体由文件分类确定
        task.setPaused(false);

        // 分类文件
        List<Map<String, Object>> fileClassifications = new ArrayList<>();
        if (path.toFile().isDirectory()) {
            Files.walk(path)
                    .filter(Files::isRegularFile)
                    .forEach(filePath -> {
                        try {
                            fileClassifications.add(classifySingleFile(filePath, sensitivePattern));
                        } catch (Exception e) {
                            Map<String, Object> errorClassification = new HashMap<>();
                            errorClassification.put("filePath", filePath.toString());
                            errorClassification.put("backupCount", 0);
                            errorClassification.put("reason", "分类失败: " + e.getMessage());
                            fileClassifications.add(errorClassification);
                        }
                    });
        } else {
            fileClassifications.add(classifySingleFile(path, sensitivePattern));
        }

        // 计算总备份次数和大小
        int totalBackupCount = fileClassifications.stream()
                .mapToInt(classification -> (int) classification.get("backupCount"))
                .sum();
        long totalSize = fileClassifications.stream()
                .filter(classification -> (int) classification.get("backupCount") > 0)
                .mapToLong(classification -> {
                    try {
                        return Files.size(Paths.get((String) classification.get("filePath")));
                    } catch (Exception e) {
                        return 0L;
                    }
                })
                .sum() * totalBackupCount;

        task.setBackupCount(totalBackupCount);
        task.setTotalSize(totalSize);
        taskRepository.save(task);

        // 返回分类结果
        Map<String, Object> result = new HashMap<>();
        result.put("task", task);
        result.put("fileClassifications", fileClassifications);
        return result;
    }

    private Map<String, Object> classifySingleFile(Path file, String sensitivePattern) throws Exception {
        Map<String, Object> classification = new HashMap<>();
        classification.put("filePath", file.toString());

        // 检查敏感词
        boolean isSensitive = Pattern.compile(sensitivePattern).matcher(file.getFileName().toString()).find();
        if (isSensitive) {
            classification.put("backupCount", 2);
            classification.put("reason", "Sensitive");
            return classification;
        }

        // 检查冷数据
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        LocalDateTime lastModified = LocalDateTime.ofInstant(
                attrs.lastModifiedTime().toInstant(),
                java.time.ZoneId.systemDefault()
        );
        long daysSinceModified = ChronoUnit.DAYS.between(lastModified, LocalDateTime.now());
        if (daysSinceModified > COLD_DATA_THRESHOLD_DAYS) {
            classification.put("backupCount", 1);
            classification.put("reason", "Cold Data");
            classification.put("lastModified", lastModified.toString());
            return classification;
        }

        classification.put("backupCount", 0);
        classification.put("reason", "Normal");
        classification.put("lastModified", lastModified.toString());
        return classification;
    }
}