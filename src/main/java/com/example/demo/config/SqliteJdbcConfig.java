package com.example.demo.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.relational.core.dialect.Dialect;

@Configuration
public class SqliteJdbcConfig {

    @Bean
    public Dialect jdbcDialect() {
        return SqliteDialect.INSTANCE;
    }
}