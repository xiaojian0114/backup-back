package com.example.demo.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

// 标记为写入数据库时的转换器（从实体类类型→数据库类型）
@WritingConverter
public class BooleanToIntegerConverter implements Converter<Boolean, Integer> {

    @Override
    public Integer convert(Boolean source) {
        // 实体类 true 对应数据库 1，false 对应 0（根据实际业务调整）
        return source != null && source ? 1 : 0;
    }
}
