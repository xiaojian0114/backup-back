package com.example.demo.service;


import com.example.demo.entity.BackupLog;
import com.example.demo.repository.BackupLogRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Service
public class IndexService {
    @Autowired
    private BackupLogRepository logRepository;

    public void generateIndex(Long taskId) throws Exception {
        List<BackupLog> logs = (List<BackupLog>) logRepository.findAllByTaskId(taskId);
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Backup Index");

        // 创建表头
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("ID");
        header.createCell(1).setCellValue("Filename");
        header.createCell(2).setCellValue("Disk ID");
        header.createCell(3).setCellValue("Target Path");
        header.createCell(4).setCellValue("Backup Time");
        header.createCell(5).setCellValue("Checksum");

        // 填充数据
        int rowNum = 1;
        for (BackupLog log : logs) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(log.getId());
            row.createCell(1).setCellValue(log.getFilename());
            row.createCell(2).setCellValue(log.getDiskId());
            row.createCell(3).setCellValue(log.getTargetPath());
            row.createCell(4).setCellValue(log.getBackupTime());
            row.createCell(5).setCellValue(log.getChecksum());
        }

        // 保存索引文件
        String indexPath = "/backup/index_" + taskId + ".xlsx";
        try (FileOutputStream fos = new FileOutputStream(indexPath)) {
            workbook.write(fos);
        }
        workbook.close();

        // 更新 backup_log.index_path
        for (BackupLog log : logs) {
            log.setIndexPath(indexPath);
            logRepository.save(log);
        }

        // 同步到目标盘和云端（模拟 SMB 和云存储）
        Files.copy(Paths.get(indexPath), Paths.get("/smb/share/index_" + taskId + ".xlsx"), StandardCopyOption.REPLACE_EXISTING);
        // 云存储API调用（示例，需替换为实际云存储API）
        // CloudStorageApi.upload(indexPath);
    }
}