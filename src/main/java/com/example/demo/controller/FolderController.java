package com.example.demo.controller;

import com.example.demo.model.AnalyzeResponse;
import com.example.demo.model.FileInfo;
import com.example.demo.service.FolderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
@CrossOrigin  // 允许跨域请求
@RestController
public class FolderController {

    private final FolderService folderService;

    @Autowired
    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    // 接口返回类型改为AnalyzeResponse（包含rootNode和stats）
    @GetMapping("/api/analyze")
    public AnalyzeResponse analyzeFolder(
            @RequestParam String path,
            @RequestParam(required = false) String keywords
    ) throws IOException {
        return folderService.analyzeFolder(path, keywords);
    }
}