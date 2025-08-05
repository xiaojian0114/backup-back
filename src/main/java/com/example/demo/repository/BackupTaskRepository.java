package com.example.demo.repository;

import com.example.demo.entity.BackupTask;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

public interface BackupTaskRepository extends CrudRepository<BackupTask, Long> {
    @Query("SELECT * FROM backup_task WHERE status = :status")
    Iterable<BackupTask> findByStatus(String status);
}