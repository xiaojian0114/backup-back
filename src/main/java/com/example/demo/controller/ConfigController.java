package com.example.demo.controller;

import com.example.demo.entity.Config;
import com.example.demo.entity.ResponseResult;
import com.example.demo.repository.ConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    @Autowired
    private ConfigRepository configRepository;

    public static final String SENSITIVE_KEYWORDS = "sensitive_keywords";
    public static final String MIGRATION_THRESHOLD = "migration_threshold";
    public static final String LAST_FILE_ACCESS_TIME = "last_file_access_time";


    @GetMapping
    public ResponseResult<Map<String, String>> getConfig(@RequestParam String keys) {
        try {
            Map<String, String> configMap = new HashMap<>();
            String[] keyArray = keys.split(",");

            for (String key : keyArray) {
                initConfigIfNotExists(key);

                Config config = configRepository.findByConfigKey(key);
                configMap.put(key, config != null ? config.getConfigValue() : "");
            }
            return ResponseResult.success(configMap, "获取配置成功");
        } catch (Exception e) {
            return ResponseResult.fail("获取配置失败：" + e.getMessage());
        }
    }


    @PutMapping
    public ResponseResult<Config> updateConfig(@RequestBody Map<String, String> request) {
        try {
            String key = request.get("key");
            String value = request.get("value");
            if (key == null || value == null) {
                return ResponseResult.fail("key和value不能为空");
            }

            Config config = configRepository.findByConfigKey(key);
            if (config == null) {
                config = new Config();
                config.setConfigKey(key);
                config.setDescription(getDefaultDescription(key));
            }
            config.setConfigValue(value);
            configRepository.save(config);
            return ResponseResult.success(config, "配置更新成功");
        } catch (Exception e) {
            return ResponseResult.fail("配置更新失败：" + e.getMessage());
        }
    }


    private void initConfigIfNotExists(String key) {
        if (configRepository.findByConfigKey(key) != null) {
            return;
        }

        Config config = new Config();
        config.setConfigKey(key);
        config.setDescription(getDefaultDescription(key));

        switch (key) {
            case SENSITIVE_KEYWORDS:
                config.setConfigValue("强奸|杀人|抢劫");
                break;
            case MIGRATION_THRESHOLD:
                config.setConfigValue("53687091200");
                break;
            case LAST_FILE_ACCESS_TIME:
                LocalDateTime oneYearAgo = LocalDateTime.now().minusYears(1);
                config.setConfigValue(oneYearAgo.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                break;
            default:
                config.setConfigValue("");
        }

        configRepository.save(config);
    }


    private String getDefaultDescription(String key) {
        switch (key) {
            case SENSITIVE_KEYWORDS:
                return "敏感关键词正则表达式（用|分隔）";
            case MIGRATION_THRESHOLD:
                return "硬盘迁移阈值（单位：字节）";
            case LAST_FILE_ACCESS_TIME:
                return "文件访问时间阈值（早于此时间视为冷数据）";
            default:
                return "系统配置项";
        }
    }
}