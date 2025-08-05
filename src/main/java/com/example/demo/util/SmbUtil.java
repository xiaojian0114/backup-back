package com.example.demo.util;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;

/**
 * SMB协议工具类：获取远程共享的总容量和可用容量
 */
public class SmbUtil {
    private static final Logger logger = LoggerFactory.getLogger(SmbUtil.class);

    /**
     * 获取SMB共享的容量信息
     *
     * @param smbUrl   SMB共享路径（如 smb://121.4.51.19/sambauser）
     * @param username
     * @param password 登录密码
     * @return 容量数组 [总容量(字节), 可用容量(字节)]
     * @throws Exception
     */
    public static long[] getSmbDiskSpace(String smbUrl, String username, String password) throws Exception {
        logger.info("Attempting to access SMB share: {}", smbUrl);
        logger.debug("Username: {}", username);

        // 配置JCIFS属性
        Properties properties = new Properties();
        // 设置协议版本
        properties.setProperty("jcifs.smb.client.minVersion", "SMB210"); // SMB 2.0
        properties.setProperty("jcifs.smb.client.maxVersion", "SMB311"); // SMB 3.1.1
        properties.setProperty("jcifs.smb.client.disableSMB1", "true"); // 禁用 SMB1
        // 超时设置
        properties.setProperty("jcifs.smb.client.soTimeout", "15000"); // 增加到 15 秒
        properties.setProperty("jcifs.smb.client.responseTimeout", "15000");
        // 禁用 NetBIOS 解析
        properties.setProperty("jcifs.netbios.disable", "true");
        properties.setProperty("jcifs.resolveOrder", "DNS"); // 优先使用 DNS
        // 其他配置
        properties.setProperty("jcifs.smb.client.dfs.disabled", "true"); // 禁用 DFS
        properties.setProperty("jcifs.smb.client.useExtendedSecurity", "true");
        // 禁用签名验证（如果服务器不支持）
        properties.setProperty("jcifs.smb.client.signingPreferred", "false");
        properties.setProperty("jcifs.smb.client.signingRequired", "false");

        try {
            // 创建配置上下文
            PropertyConfiguration config = new PropertyConfiguration(properties);
            CIFSContext baseContext = new BaseContext(config);

            // 解析认证信息
            String domain = "";
            String user = username;
            if (username.contains("\\")) {
                String[] parts = username.split("\\\\", 2);
                domain = parts[0];
                user = parts[1];
            }
            logger.debug("Domain: {}, User: {}", domain, user);

            // 创建认证上下文
            NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(domain, user, password);
            CIFSContext authContext = baseContext.withCredentials(auth);

            // 获取容量信息
            try (SmbFile smbFile = new SmbFile(smbUrl, authContext)) {
                logger.debug("Checking if SMB path exists: {}", smbUrl);
                if (!smbFile.exists()) {
                    logger.error("SMB path does not exist: {}", smbUrl);
                    throw new IllegalArgumentException("SMB路径不存在：" + smbUrl);
                }
                logger.debug("Checking if SMB path is directory: {}", smbUrl);
                if (!smbFile.isDirectory()) {
                    logger.error("SMB path is not a directory: {}", smbUrl);
                    throw new IllegalArgumentException("SMB路径不是目录：" + smbUrl);
                }

                long freeSpace = smbFile.getDiskFreeSpace();
                long totalSpace = freeSpace + smbFile.length();
                logger.info("SMB share info - Total: {} bytes, Free: {} bytes", totalSpace, freeSpace);

                return new long[]{totalSpace, freeSpace};
            }
        } catch (SmbException e) {
            logger.error("SMB connection failed: {}", e.getMessage(), e);
            throw new Exception("Failed to connect to SMB: " + e.getMessage(), e);
        } catch (IOException e) {
            logger.error("IO error accessing SMB: {}", e.getMessage(), e);
            throw new Exception("IO error: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error accessing SMB: {}", e.getMessage(), e);
            throw new Exception("Unexpected error: " + e.getMessage(), e);
        }
    }
}