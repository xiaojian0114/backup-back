package com.example.demo.util;

import com.example.demo.entity.HardDisk;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiskScanner {

    // 使用原子计数器确保线程安全
    private static final AtomicInteger windowsCounter = new AtomicInteger(1);
    private static final AtomicInteger linuxCounter = new AtomicInteger(1);

    // 校验字符串是否为有效数字（允许前后空格）
    private static boolean isNumeric(String str) {
        if (str == null) return false;
        return str.trim().matches("\\d+");
    }

    // 重置计数器（每次扫描前调用）
    public static void resetCounters() {
        windowsCounter.set(1);
        linuxCounter.set(1);
    }

    public static List<HardDisk> scanPhysicalDisks() throws IOException {
        // 重置计数器确保每次扫描都从1开始
        resetCounters();

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return scanWindowsDisks();
        } else if (os.contains("nix") || os.contains("nux")) {
            return scanLinuxDisks();
        } else {
            throw new UnsupportedOperationException("不支持的操作系统");
        }
    }

    // 扫描Windows磁盘（修复解析逻辑和可用空间获取）
    private static List<HardDisk> scanWindowsDisks() throws IOException {
        System.out.println("\n=== 开始扫描Windows磁盘 ===");
        List<HardDisk> disks = new ArrayList<>();

        // 执行WMIC命令获取物理磁盘信息
        Process process = Runtime.getRuntime().exec(new String[]{
                "cmd", "/c", "wmic diskdrive get DeviceID,SerialNumber,Size /format:value"
        });

        // 存储物理磁盘信息：{DeviceID -> SerialNumber}
        Map<String, String> physicalDisks = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "GBK"))) {

            String line;
            String deviceId = null;
            String serialNumber = null;
            String sizeStr = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("DeviceID=")) {
                    deviceId = line.substring("DeviceID=".length()).trim();
                } else if (line.startsWith("SerialNumber=")) {
                    serialNumber = line.substring("SerialNumber=".length()).trim();
                } else if (line.startsWith("Size=")) {
                    sizeStr = line.substring("Size=".length()).trim();
                }

                // 当收集完一个磁盘的所有信息后
                if (deviceId != null && serialNumber != null && sizeStr != null) {
                    if (isNumeric(sizeStr)) {
                        physicalDisks.put(deviceId, serialNumber);
                        System.out.println("找到物理磁盘: " + deviceId + ", 序列号: " + serialNumber);
                    }
                    // 重置
                    deviceId = null;
                    serialNumber = null;
                    sizeStr = null;
                }
            }
        }

        // 执行WMIC命令获取逻辑驱动器与物理磁盘的映射关系
        process = Runtime.getRuntime().exec(new String[]{
                "cmd", "/c", "wmic logicaldisk to diskdrive get Caption,Dependent /format:value"
        });

        // 存储逻辑驱动器到物理磁盘的映射：{DriveLetter -> DeviceID}
        Map<String, String> driveToDiskMap = new HashMap<>();
        Pattern deviceIdPattern = Pattern.compile("DeviceID=\"(.*?)\"");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "GBK"))) {

            String line;
            String driveLetter = null;
            String deviceId = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("Caption=")) {
                    driveLetter = line.substring("Caption=".length()).trim();
                } else if (line.startsWith("Dependent=")) {
                    String dependent = line.substring("Dependent=".length()).trim();
                    Matcher matcher = deviceIdPattern.matcher(dependent);
                    if (matcher.find()) {
                        deviceId = matcher.group(1);
                    }
                }

                // 当收集完一个映射的所有信息后
                if (driveLetter != null && deviceId != null) {
                    driveToDiskMap.put(driveLetter, deviceId);
                    System.out.println("映射: " + driveLetter + " -> " + deviceId);
                    // 重置
                    driveLetter = null;
                    deviceId = null;
                }
            }
        }

        // 扫描所有逻辑驱动器并获取真实可用空间
        File[] roots = File.listRoots();
        for (File root : roots) {
            try {
                String driveLetter = root.getAbsolutePath();
                if (driveLetter.endsWith("\\")) {
                    driveLetter = driveLetter.substring(0, driveLetter.length() - 1);
                }

                // 跳过不可读或容量过小的驱动器
                if (!root.canRead() || root.getTotalSpace() < 1024 * 1024 * 100) {
                    System.out.println("跳过无效驱动器: " + driveLetter);
                    continue;
                }

                // 获取对应的物理磁盘序列号
                String deviceId = driveToDiskMap.get(driveLetter);
                String serialNumber = deviceId != null ?
                        physicalDisks.getOrDefault(deviceId, "UNKNOWN_" + driveLetter) :
                        "UNKNOWN_" + driveLetter;

                // 创建磁盘对象 - 使用固定格式ID
                HardDisk disk = new HardDisk();
                disk.setDiskId(DiskIdGenerator.generateDiskId(1, windowsCounter.getAndIncrement()));
                disk.setSerialNumber(serialNumber);
                disk.setTotalCapacity(root.getTotalSpace());
                disk.setAvailableCapacity(root.getUsableSpace());
                disk.setMountPoint(driveLetter + "\\");
                disk.setStatus("ACTIVE");
                disk.setProtocol("LOCAL"); // 明确设置协议类型

                disks.add(disk);
                System.out.println("✔️ 添加磁盘: " + driveLetter +
                        ", ID: " + disk.getDiskId() +
                        ", 序列号: " + serialNumber +
                        ", 总容量: " + formatSize(root.getTotalSpace()) +
                        ", 可用容量: " + formatSize(root.getUsableSpace()));
            } catch (Exception e) {
                System.err.println("处理驱动器 " + root + " 失败: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("=== Windows磁盘扫描完成，共发现 " + disks.size() + " 个磁盘 ===\n");
        return disks;
    }

    // 格式化磁盘容量为人类可读的格式
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "i";
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // Linux磁盘扫描（保持原有逻辑，优化可用空间获取）
    private static List<HardDisk> scanLinuxDisks() throws IOException {
        System.out.println("\n=== 开始扫描Linux磁盘 ===");
        List<HardDisk> disks = new ArrayList<>();

        // 获取所有块设备信息
        Process process = Runtime.getRuntime().exec(new String[]{
                "sh", "-c", "lsblk -b -o NAME,SERIAL,SIZE,TYPE,MOUNTPOINT --json"
        });

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            StringBuilder jsonBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBuilder.append(line);
            }

            String json = jsonBuilder.toString();
            // 简化的JSON解析（实际项目中建议使用真正的JSON解析库）
            // 提取磁盘信息
            Pattern diskPattern = Pattern.compile("\\{\"name\":\"(.*?)\",\"serial\":\"(.*?)\",\"size\":(\\d+),\"type\":\"disk\",\"mountpoint\":\"(.*?)\"\\}");
            Matcher matcher = diskPattern.matcher(json);

            int diskCount = 0;
            while (matcher.find()) {
                diskCount++;
                String name = matcher.group(1);
                String serial = matcher.group(2).isEmpty() ? "UNKNOWN_" + name : matcher.group(2);
                long size = Long.parseLong(matcher.group(3));
                String mountPoint = matcher.group(4).isEmpty() ? "/mnt/" + name : matcher.group(4);

                // 创建默认挂载点目录（如果不存在）
                File mountDir = new File(mountPoint);
                if (!mountDir.exists() && !mountDir.mkdirs()) {
                    System.out.println("无法创建挂载点目录: " + mountPoint);
                    mountPoint = "/"; // 回退到根目录
                }

                // 获取可用空间
                long availableSpace = 0;
                try {
                    availableSpace = mountDir.getUsableSpace();
                } catch (Exception e) {
                    System.err.println("获取 " + mountPoint + " 可用空间失败: " + e.getMessage());
                }

                // 使用固定格式ID
                HardDisk disk = new HardDisk();
                disk.setDiskId(DiskIdGenerator.generateDiskId(2, linuxCounter.getAndIncrement()));
                disk.setSerialNumber(serial);
                disk.setTotalCapacity(size);
                disk.setAvailableCapacity(availableSpace);
                disk.setMountPoint(mountPoint);
                disk.setStatus("ACTIVE");
                disk.setProtocol("LOCAL"); // 明确设置协议类型

                disks.add(disk);
                System.out.println("✔️ 添加Linux磁盘: " + name +
                        ", ID: " + disk.getDiskId() +
                        ", 挂载点: " + mountPoint +
                        ", 总容量: " + formatSize(size) +
                        ", 可用容量: " + formatSize(availableSpace));
            }

            System.out.println("=== Linux磁盘扫描完成，共发现 " + diskCount + " 个磁盘 ===\n");
        } catch (Exception e) {
            System.err.println("Linux磁盘扫描异常: " + e.getMessage());
            e.printStackTrace();
            throw new IOException("Linux磁盘扫描失败: " + e.getMessage(), e);
        }

        return disks;
    }
}