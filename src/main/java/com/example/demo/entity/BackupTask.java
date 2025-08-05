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
    private String status;

    @Column("is_sensitive")
    private Boolean isSensitive;

    @Column("backup_count")
    private Integer backupCount;


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
        isSensitive = sensitive;
    }

    public Integer getBackupCount() {
        return backupCount;
    }

    public void setBackupCount(Integer backupCount) {
        this.backupCount = backupCount;
    }
}
