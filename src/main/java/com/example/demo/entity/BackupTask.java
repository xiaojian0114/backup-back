package com.example.demo.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("backup_task")
public class BackupTask {
    @Id
    @Column("id")
    private Long id;

    @Column("source_path")
    private String sourcePath;

    @Column("schedule")
    private String schedule;

    @Column("backup_mode")
    private String backupMode;

    @Column("status")
    private String status; // PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED

    @Column("is_sensitive")
    private Boolean isSensitive;

    @Column("backup_count")
    private Integer backupCount;

    @Column("paused")
    private boolean paused;

    @Column("target_disk_id")
    private String targetDiskId;

    @Column("total_size")
    private long totalSize; // 新增：总文件大小

    @Column("completed_size")
    private long completedSize; // 新增：已完成文件大小

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getSchedule() {
        return schedule;
    }

    public void setSchedule(String schedule) {
        this.schedule = schedule;
    }

    public String getBackupMode() {
        return backupMode;
    }

    public void setBackupMode(String backupMode) {
        this.backupMode = backupMode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getSensitive() {
        return isSensitive;
    }

    public void setSensitive(Boolean sensitive) {
        this.isSensitive = sensitive;
    }

    public Integer getBackupCount() {
        return backupCount;
    }

    public void setBackupCount(Integer backupCount) {
        this.backupCount = backupCount;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public String getTargetDiskId() {
        return targetDiskId;
    }

    public void setTargetDiskId(String targetDiskId) {
        this.targetDiskId = targetDiskId;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }

    public long getCompletedSize() {
        return completedSize;
    }

    public void setCompletedSize(long completedSize) {
        this.completedSize = completedSize;
    }
}