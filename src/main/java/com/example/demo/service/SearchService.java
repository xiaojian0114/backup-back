package com.example.demo.service;


import com.example.demo.entity.BackupLog;
import com.example.demo.repository.BackupLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SearchService {
    @Autowired
    private BackupLogRepository logRepository;

    public List<BackupLog> searchFiles(String keyword) {
        List<BackupLog> results = new ArrayList<>();
        for (BackupLog log : logRepository.findAll()) {
            if (log.getFilename().contains(keyword) || log.getTargetPath().contains(keyword)) {
                results.add(log);
            }
        }
        // 并行查询目标盘和云端（模拟）
        // results.addAll(smbSearch(keyword));
        // results.addAll(cloudSearch(keyword));
        return results;
    }
}