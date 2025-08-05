package com.example.demo.service;

import com.example.demo.model.AnalyzeResponse;
import com.example.demo.model.FileInfo;
import com.example.demo.model.Stats;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

@Service
public class FolderService {

    private static final String DEFAULT_SENSITIVE_KEYWORDS = "杀人|强奸|抢劫|个人";

    // 对外暴露的方法：返回包含树形结构和统计的响应
    public AnalyzeResponse analyzeFolder(String folderPath, String sensitiveKeywords) throws IOException {
        if (sensitiveKeywords == null || sensitiveKeywords.isEmpty()) {
            sensitiveKeywords = DEFAULT_SENSITIVE_KEYWORDS;
        }

        Path rootPath = Paths.get(folderPath);
        // 验证路径合法性
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("路径不存在或不是目录: " + folderPath);
        }

        // 1. 递归构建文件树（根节点）
        FileInfo rootNode = buildFileTree(rootPath, sensitiveKeywords);
        // 2. 计算统计信息
        Stats stats = calculateStats(rootNode);

        return new AnalyzeResponse(rootNode, stats);
    }

    // 递归构建文件树（核心方法）
    private FileInfo buildFileTree(Path path, String sensitiveKeywords) throws IOException {
        FileInfo node = new FileInfo();
        node.setName(path.getFileName().toString());
        node.setPath(path.toString());
        node.setDirectory(Files.isDirectory(path));

        // 处理文件时间（将FileTime转为String，避免序列化异常）
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        node.setLastModifiedTime(attrs.lastModifiedTime().toString());  // 转为字符串

        // 关键修改：对所有节点（包括目录）进行敏感词检测
        String safeKeywords = sensitiveKeywords.replaceAll("[.*+?^${}()\\[\\]\\\\]", "\\\\$0");
        // 正则匹配：不区分大小写，匹配任意关键词
        boolean isSensitive = node.getName().matches("(?i).*(" + safeKeywords + ").*");
        node.setSensitive(isSensitive);

        if (Files.isDirectory(path)) {
            // 目录：递归处理子节点
            List<FileInfo> children = new ArrayList<>();
            // 遍历子目录/文件（使用try-with-resources确保流关闭）
            try (var stream = Files.list(path)) {
                stream.forEach(childPath -> {
                    try {
                        children.add(buildFileTree(childPath, sensitiveKeywords));
                    } catch (IOException e) {
                        // 忽略无权限的文件/目录，避免整个遍历失败
                        System.err.println("无法访问 " + childPath + "，原因：" + e.getMessage());
                    }
                });
            }
            node.setChildren(children);
        } else {
            // 文件：设置大小
            node.setSize(attrs.size());
        }

        return node;
    }

    // 计算统计信息（递归累加）
    private Stats calculateStats(FileInfo node) {
        Stats stats = new Stats();

        if (node.isDirectory()) {
            // 目录：自身计数+子节点计数
            stats.setTotalDirectories(1);  // 自身算一个目录
            if (node.isSensitive()) {
                stats.setSensitiveFiles(1);  // 敏感目录计数
            }
            if (node.getChildren() != null) {
                for (FileInfo child : node.getChildren()) {
                    Stats childStats = calculateStats(child);
                    stats.setTotalFiles(stats.getTotalFiles() + childStats.getTotalFiles());
                    stats.setTotalDirectories(stats.getTotalDirectories() + childStats.getTotalDirectories());
                    stats.setSensitiveFiles(stats.getSensitiveFiles() + childStats.getSensitiveFiles());
                    stats.setTotalSize(stats.getTotalSize() + childStats.getTotalSize());
                }
            }
        } else {
            // 文件：自身计数
            stats.setTotalFiles(1);  // 自身算一个文件
            stats.setTotalSize(node.getSize());
            if (node.isSensitive()) {
                stats.setSensitiveFiles(1);  // 敏感文件计数
            }
        }

        return stats;
    }
}