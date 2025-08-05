package com.example.demo.service;

import com.example.demo.entity.Config;
import com.example.demo.entity.HardDisk;
import com.example.demo.repository.ConfigRepository;
import com.example.demo.repository.HardDiskRepository;
import com.example.demo.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Slf4j
@Service
public class DiskManagementService {
    @Autowired
    private HardDiskRepository diskRepository;
    @Autowired
    private ConfigRepository configRepository;

    private static final long MIGRATION_THRESHOLD = 50L * 1024 * 1024 * 1024; // 50GB

    public HardDisk initializeDisk(String serialNumber, long totalCapacity) throws Exception {
        // 使用Optional避免空指针，通过序列号检查是否存在
        Optional<HardDisk> existingDiskOpt = diskRepository.findBySerialNumber(serialNumber);
        if (existingDiskOpt.isPresent()) {
            return existingDiskOpt.get();
        }

        // 生成新磁盘ID（使用固定格式）
        String diskId = DiskIdGenerator.generateDiskId(1, (int) (diskRepository.count() + 1));

        // 初始化磁盘（模拟 parted 和 mkfs.ext4）
        String mountPoint = "/backup/" + diskId;
        Files.createDirectories(Paths.get(mountPoint));

        // 创建新磁盘记录
        HardDisk disk = new HardDisk();
        disk.setDiskId(diskId);
        disk.setSerialNumber(serialNumber);
        disk.setTotalCapacity(totalCapacity);
        disk.setAvailableCapacity(totalCapacity);
        disk.setStatus("ACTIVE");
        disk.setMountPoint(mountPoint);
        disk.setProtocol("LOCAL"); // 添加协议类型
        return diskRepository.save(disk);
    }

    public HardDisk initializeRemoteSmbDisk(String smbUrl, String username, String encryptedPwd, String serialNumber) throws Exception {
        try {
            log.info("Initializing SMB disk: {}, username: {}, encrypted password: {}", smbUrl, username, encryptedPwd);
            String decryptedPwd = CryptoUtil.decrypt(encryptedPwd);
            log.debug("Decrypted password length: {}", decryptedPwd.length());

            long[] spaces = SmbUtil.getSmbDiskSpace(smbUrl, username, decryptedPwd);
            long totalCapacity = spaces[0];
            long availableCapacity = spaces[1];
            log.info("SMB disk capacity - Total: {} bytes, Available: {} bytes", totalCapacity, availableCapacity);

            // 使用Optional检查是否存在
            Optional<HardDisk> existingDiskOpt = diskRepository.findBySerialNumber(serialNumber);
            if (existingDiskOpt.isPresent()) {
                log.info("SMB disk already exists: {}", serialNumber);
                return existingDiskOpt.get();
            }

            // 使用固定格式ID
            String diskId = DiskIdGenerator.generateDiskId(3, (int) (diskRepository.count() + 1));
            HardDisk disk = new HardDisk();
            disk.setDiskId(diskId);
            disk.setSerialNumber(serialNumber);
            disk.setTotalCapacity(totalCapacity);
            disk.setAvailableCapacity(availableCapacity);
            disk.setStatus("ACTIVE");
            disk.setMountPoint(smbUrl);
            disk.setProtocol("SMB");
            disk.setRemoteUrl(smbUrl);
            disk.setRemoteUsername(username);
            disk.setRemotePasswordEncrypted(encryptedPwd);
            HardDisk savedDisk = diskRepository.save(disk);
            log.info("SMB disk initialized successfully: {}", diskId);
            return savedDisk;
        } catch (Exception e) {
            log.error("SMB initialization failed for {}: {}", smbUrl, e.getMessage(), e);
            throw new RuntimeException("Failed to initialize: " + e.getMessage(), e);
        }
    }

    public void migrateDisk(HardDisk sourceDisk, HardDisk targetDisk) throws Exception {
        if (targetDisk.getAvailableCapacity() < sourceDisk.getTotalCapacity() * 1.2) {
            throw new IllegalStateException("Target disk capacity insufficient");
        }

        sourceDisk.setMigrationStatus("RUNNING");
        sourceDisk.setMigrationTarget(targetDisk.getId());
        diskRepository.save(sourceDisk);

        // 递归复制数据（仅本地磁盘支持，远程磁盘需额外实现SMB复制逻辑）
        Files.walk(Paths.get(sourceDisk.getMountPoint()))
                .filter(path -> !Files.isDirectory(path))
                .forEach(path -> {
                    try {
                        String relativePath = path.toString().substring(sourceDisk.getMountPoint().length());
                        Path targetPath = Paths.get(targetDisk.getMountPoint() + relativePath);
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);

                        // 校验文件完整性
                        String sourceChecksum = ChecksumUtil.calculateSHA256(path.toFile());
                        String targetChecksum = ChecksumUtil.calculateSHA256(targetPath.toFile());
                        if (!sourceChecksum.equals(targetChecksum)) {
                            throw new Exception("Checksum verification failed for " + path);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });

        // 更新状态
        sourceDisk.setStatus("ARCHIVED");
        sourceDisk.setMigrationStatus(null);
        sourceDisk.setMigrationTarget(null);
        targetDisk.setStatus("ACTIVE");
        diskRepository.save(sourceDisk);
        diskRepository.save(targetDisk);
    }

    /**
     * 扫描并更新所有磁盘（本地+远程）
     * 修复：使用serialNumber作为唯一标识，解决唯一约束冲突
     */
    public List<HardDisk> scanAndUpdateDisks() throws Exception {
        List<HardDisk> updatedDisks = new ArrayList<>();

        // 1. 处理本地磁盘
        List<HardDisk> physicalDisks = DiskScanner.scanPhysicalDisks();
        for (HardDisk scannedDisk : physicalDisks) {
            // 关键修复：通过serialNumber检查是否存在（与数据库唯一约束一致）
            Optional<HardDisk> existingOpt = diskRepository.findBySerialNumber(scannedDisk.getSerialNumber());

            if (existingOpt.isPresent()) {
                // 更新现有记录
                HardDisk existing = existingOpt.get();
                existing.setTotalCapacity(scannedDisk.getTotalCapacity());
                existing.setAvailableCapacity(scannedDisk.getAvailableCapacity());
                existing.setMountPoint(scannedDisk.getMountPoint());
                existing.setStatus("ACTIVE");
                existing.setDiskId(scannedDisk.getDiskId()); // 同步最新的diskId
                updatedDisks.add(diskRepository.save(existing));
                log.info("更新磁盘: {}", existing.getSerialNumber());
            } else {
                // 保存新记录
                updatedDisks.add(diskRepository.save(scannedDisk));
                log.info("添加新磁盘: {}", scannedDisk.getSerialNumber());
            }
        }

        // 2. 处理远程SMB磁盘（更新容量）
        List<HardDisk> smbDisks = StreamSupport.stream(diskRepository.findAll().spliterator(), false)
                .filter(disk -> "SMB".equals(disk.getProtocol()))
                .collect(java.util.stream.Collectors.toList());

        for (HardDisk smbDisk : smbDisks) {
            try {
                // 解密密码并更新容量
                String pwd = CryptoUtil.decrypt(smbDisk.getRemotePasswordEncrypted());
                long[] spaces = SmbUtil.getSmbDiskSpace(smbDisk.getRemoteUrl(), smbDisk.getRemoteUsername(), pwd);

                smbDisk.setTotalCapacity(spaces[0]);
                smbDisk.setAvailableCapacity(spaces[1]);
                smbDisk.setStatus("ACTIVE");
                updatedDisks.add(diskRepository.save(smbDisk));
            } catch (Exception e) {
                smbDisk.setStatus("ERROR"); // 标记连接错误
                diskRepository.save(smbDisk);
                log.error("SMB磁盘更新失败：{}", smbDisk.getDiskId(), e);
            }
        }

        return updatedDisks;
    }

    public void checkAndMigrateDisks() throws Exception {
        // 从config表获取阈值（本地/远程共用）
        Config thresholdConfig = configRepository.findByConfigKey("migration_threshold");
        long threshold = thresholdConfig != null ? Long.parseLong(thresholdConfig.getConfigValue()) : MIGRATION_THRESHOLD;

        // 遍历所有磁盘（本地+远程），检查容量是否低于阈值
        for (HardDisk disk : diskRepository.findAll()) {
            if (disk.getAvailableCapacity() < threshold || "PENDING".equals(disk.getMigrationStatus())) {
                disk.setMigrationStatus("PENDING");
                diskRepository.save(disk);
                log.warn("磁盘容量不足：{}，请处理", disk.getDiskId());
            }
        }
    }
}
