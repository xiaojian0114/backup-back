package com.example.demo.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

// 标记为读取数据库时的转换器（从数据库类型→实体类类型）
@ReadingConverter
public class IntegerToBooleanConverter implements Converter<Integer, Boolean> {

    @Override
    public Boolean convert(Integer source) {
        // 数据库中用 1 表示 true，0 表示 false（根据实际业务调整）
        return source != null && source == 1;
    }
}
