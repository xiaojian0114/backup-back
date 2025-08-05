package com.example.demo.repository;

import com.example.demo.entity.BackupLog;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface BackupLogRepository extends CrudRepository<BackupLog, Long> {
    @Query("SELECT * FROM backup_log WHERE checksum = :checksum")
    BackupLog findByChecksum(String checksum);

    @Query("SELECT * FROM backup_log WHERE task_id = :taskId")
    List<BackupLog> findAllByTaskId(Long taskId);
}