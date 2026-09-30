package com.example.test.translator.service;

import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 翻译引擎凭证的加密存取。
 * 使用 Windows DPAPI 加密后落盘，解密由 Windows 账户托管，代码中不出现密钥。
 * 文件位置：%USERPROFILE%\.translator\credentials.dat
 */
public class CredentialStore {

    private static final String DIR_NAME = ".translator";
    private static final String FILE_NAME = "credentials.dat";
    private static final char SEPARATOR = '\n';

    private final Path file;

    public CredentialStore() {
        this(Paths.get(System.getProperty("user.home"), DIR_NAME));
    }

    public CredentialStore(Path dir) {
        this.file = dir.resolve(FILE_NAME);
    }

    /**
     * 保存凭证。
     *
     * @throws IOException 参数为空或写入失败
     */
    public void save(String appId, String secretKey) throws IOException {
        if (appId == null || appId.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IOException("APPID 和密钥不能为空");
        }
        String plain = appId + SEPARATOR + secretKey;
        byte[] encrypted = Crypt32Util.cryptProtectData(plain.getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(file.getParent());
        Files.write(file, encrypted);
    }

    /**
     * 读取凭证。
     *
     * @return 两个元素的数组 [appId, secretKey]；文件不存在时返回 null
     * @throws IOException 解密或读取失败
     */
    public String[] load() throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        byte[] encrypted = Files.readAllBytes(file);
        byte[] decrypted = Crypt32Util.cryptUnprotectData(encrypted);
        String plain = new String(decrypted, StandardCharsets.UTF_8);
        int idx = plain.indexOf(SEPARATOR);
        if (idx < 0) {
            return null;
        }
        return new String[]{plain.substring(0, idx), plain.substring(idx + 1)};
    }
}
