package com.example.demo.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


@Table("hard_disk")
public class HardDisk {
    @Id
    @Column("id")

    private Long id;

    @Column("disk_id")
    @NotBlank(message = "diskId不能为空")
    private String diskId;

    @Column("serial_number")
    @NotBlank(message = "serialNumber不能为空")
    private String serialNumber;

    @Column("total_capacity")
    @NotNull(message = "totalCapacity不能为空")
    private Long totalCapacity;

    @Column("available_capacity")
    @NotNull(message = "availableCapacity不能为空")
    private Long availableCapacity;

    @Column("status")
    @NotBlank(message = "status不能为空")
    private String status;

    @Column("mount_point")
    @NotBlank(message = "mountPoint不能为空")
    private String mountPoint;

    @Column("migration_status")
    private String migrationStatus;

    @Column("migration_target")
    private Long migrationTarget;


    @Column("protocol")
    private String protocol;


    @Column("remote_url")
    private String remoteUrl;


    @Column("remote_username")
    private String remoteUsername;


    @Column("remote_password_encrypted")
    private String remotePasswordEncrypted;



    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDiskId() {
        return diskId;
    }

    public void setDiskId(String diskId) {
        this.diskId = diskId;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public Long getTotalCapacity() {
        return totalCapacity;
    }

    public void setTotalCapacity(Long totalCapacity) {
        this.totalCapacity = totalCapacity;
    }

    public Long getAvailableCapacity() {
        return availableCapacity;
    }

    public void setAvailableCapacity(Long availableCapacity) {
        this.availableCapacity = availableCapacity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMountPoint() {
        return mountPoint;
    }

    public void setMountPoint(String mountPoint) {
        this.mountPoint = mountPoint;
    }

    public String getMigrationStatus() {
        return migrationStatus;
    }

    public void setMigrationStatus(String migrationStatus) {
        this.migrationStatus = migrationStatus;
    }

    public Long getMigrationTarget() {
        return migrationTarget;
    }

    public void setMigrationTarget(Long migrationTarget) {
        this.migrationTarget = migrationTarget;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void setRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    public String getRemoteUsername() {
        return remoteUsername;
    }

    public void setRemoteUsername(String remoteUsername) {
        this.remoteUsername = remoteUsername;
    }

    public String getRemotePasswordEncrypted() {
        return remotePasswordEncrypted;
    }

    public void setRemotePasswordEncrypted(String remotePasswordEncrypted) {
        this.remotePasswordEncrypted = remotePasswordEncrypted;
    }
}