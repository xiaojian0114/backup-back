// com/example/demo/model/FileInfo.java
package com.example.demo.model;

import java.nio.file.attribute.FileTime;
import java.util.List;

public class FileInfo {
    private String name;
    private String path;
    private boolean isDirectory;  // 字段名与前端一致（前端用isDirectory）
    private long size;
    private String lastModifiedTime;  // 改为String类型，避免FileTime序列化异常
    private boolean isSensitive;
    private List<FileInfo> children;  // 新增：子节点（用于树形结构）

    // Getter和Setter（重点关注以下几个）
    public boolean isDirectory() { return isDirectory; }
    public void setDirectory(boolean directory) { isDirectory = directory; }

    // 时间改为String类型，存储格式化后的字符串
    public String getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(String lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    // 子节点相关
    public List<FileInfo> getChildren() { return children; }
    public void setChildren(List<FileInfo> children) { this.children = children; }

    // 其他原有Getter/Setter保持不变
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public boolean isSensitive() { return isSensitive; }
    public void setSensitive(boolean sensitive) { isSensitive = sensitive; }
}