package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

import java.util.Arrays;

@Configuration
@EnableJdbcRepositories(basePackages = "com.example.demo.repository") // 扫描你的Repository包
public class JdbcConverterConfig {

    // 注册自定义转换器
    @Bean
    public JdbcCustomConversions jdbcCustomConversions() {
        return new JdbcCustomConversions(Arrays.asList(
                new IntegerToBooleanConverter(),  // 读取时转换
                new BooleanToIntegerConverter()   // 写入时转换（可选）
        ));
    }
}
