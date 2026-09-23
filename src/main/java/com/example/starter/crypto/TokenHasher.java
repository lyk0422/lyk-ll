package com.example.starter.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 令牌 SHA-256 摘要工具：数据库只保存摘要，绝不保存明文。
 */
@Component
public class TokenHasher {

    private static final HexFormat HEX = HexFormat.of();

    /**
     * 计算令牌明文的 SHA-256 十六进制摘要。
     */
    public String sha256(String plaintext) {
        return hash(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算任意字节的 SHA-256 十六进制摘要。
     */
    public String hash(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
