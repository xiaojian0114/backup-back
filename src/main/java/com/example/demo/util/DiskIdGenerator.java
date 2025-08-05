package com.example.demo.util;

import java.time.LocalDate;

public class DiskIdGenerator {
    // 生成固定格式的磁盘ID
    public static String generateDiskId(int group, int index) {
        String yearMonth = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        char groupChar = (char) ('A' + group - 1);
        return String.format("%s%s%03d", yearMonth, groupChar, index);
    }
}