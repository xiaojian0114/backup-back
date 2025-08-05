// com/example/demo/model/AnalyzeResponse.java
package com.example.demo.model;

public class AnalyzeResponse {
    private FileInfo rootNode;  // 根节点（树形结构）
    private Stats stats;        // 统计信息

    // 构造器
    public AnalyzeResponse(FileInfo rootNode, Stats stats) {
        this.rootNode = rootNode;
        this.stats = stats;
    }

    // Getter
    public FileInfo getRootNode() { return rootNode; }
    public Stats getStats() { return stats; }
}