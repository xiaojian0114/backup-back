package com.example.demo.repository;


import com.example.demo.entity.Config;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

public interface ConfigRepository extends CrudRepository<Config, Long> {
    @Query("SELECT * FROM config WHERE config_key = :key")
    Config findByConfigKey(String key);
}
