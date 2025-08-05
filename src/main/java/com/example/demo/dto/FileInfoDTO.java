package com.example.demo.dto;

import java.io.File;


public class FileInfoDTO {
    private String name;
    private String path;
    private boolean isDirectory;
    private long size;
    private long lastModified;


    public FileInfoDTO(File file) {
        this.name = file.getName();
        this.path = file.getAbsolutePath();
        this.isDirectory = file.isDirectory();
        this.size = file.isFile() ? file.length() : 0;
        this.lastModified = file.lastModified();
    }


    public String getName() { return name; }
    public String getPath() { return path; }
    public boolean isDirectory() { return isDirectory; }
    public long getSize() { return size; }
    public long getLastModified() { return lastModified; }
}