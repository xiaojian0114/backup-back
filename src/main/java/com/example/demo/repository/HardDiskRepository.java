package com.example.demo.repository;

import com.example.demo.entity.HardDisk;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import java.util.Optional;

public interface HardDiskRepository extends CrudRepository<HardDisk, Long> {

    @Query("SELECT * FROM hard_disk WHERE disk_id = :diskId")
    HardDisk findByDiskId(String diskId);

    /**
     * 修复：返回Optional类型，避免空指针异常
     * 与数据库serial_number唯一约束对应
     */
    @Query("SELECT * FROM hard_disk WHERE serial_number = :serialNumber")
    Optional<HardDisk> findBySerialNumber(String serialNumber);

    HardDisk findBySerialNumberAndMountPoint(String serialNumber, String mountPoint);
}
