// com/example/demo/model/Stats.java
package com.example.demo.model;

public class Stats {
    private int totalFiles;         // 总文件数
    private int totalDirectories;   // 总目录数
    private int sensitiveFiles;     // 敏感文件数
    private long totalSize;         // 总大小（字节）

    // Getter和Setter
    public int getTotalFiles() { return totalFiles; }
    public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }

    public int getTotalDirectories() { return totalDirectories; }
    public void setTotalDirectories(int totalDirectories) { this.totalDirectories = totalDirectories; }

    public int getSensitiveFiles() { return sensitiveFiles; }
    public void setSensitiveFiles(int sensitiveFiles) { this.sensitiveFiles = sensitiveFiles; }

    public long getTotalSize() { return totalSize; }
    public void setTotalSize(long totalSize) { this.totalSize = totalSize; }
}