package com.example.demo.util;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.logging.Logger;

public class CryptoUtil {
    private static final Logger LOGGER = Logger.getLogger(CryptoUtil.class.getName());
    private static final String SECRET_KEY = "your-16byte-key!"; // 16字节
    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";

    public static String encrypt(String plainText) throws Exception {
        try {
            if (plainText == null || plainText.isEmpty()) {
                throw new IllegalArgumentException("明文不能为空");
            }
            SecretKeySpec key = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv); // 随机IV
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            // 将IV和密文拼接
            byte[] ivAndEncrypted = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, ivAndEncrypted, 0, iv.length);
            System.arraycopy(encrypted, 0, ivAndEncrypted, iv.length, encrypted.length);
            String encoded = Base64.getEncoder().encodeToString(ivAndEncrypted);
            LOGGER.info("加密成功，密文: " + encoded);
            return encoded;
        } catch (Exception e) {
            LOGGER.severe("加密失败: " + e.getMessage());
            throw new RuntimeException("密码加密失败", e);
        }
    }

    public static String decrypt(String encryptedText) throws Exception {
        try {
            if (encryptedText == null || encryptedText.isEmpty()) {
                throw new IllegalArgumentException("密文不能为空");
            }
            byte[] ivAndEncrypted = Base64.getDecoder().decode(encryptedText);
            if (ivAndEncrypted.length < 16) {
                throw new IllegalArgumentException("无效的密文长度");
            }
            byte[] iv = Arrays.copyOfRange(ivAndEncrypted, 0, 16);
            byte[] encrypted = Arrays.copyOfRange(ivAndEncrypted, 16, ivAndEncrypted.length);
            SecretKeySpec key = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);
            byte[] decrypted = cipher.doFinal(encrypted);
            String result = new String(decrypted, StandardCharsets.UTF_8);
            LOGGER.info("解密成功");
            return result;
        } catch (IllegalArgumentException e) {
            LOGGER.severe("解密失败，无效的Base64密文: " + encryptedText);
            throw new RuntimeException("无效的密文格式", e);
        } catch (Exception e) {
            LOGGER.severe("解密失败: " + e.getMessage());
            throw new RuntimeException("密码解密失败", e);
        }
    }
}