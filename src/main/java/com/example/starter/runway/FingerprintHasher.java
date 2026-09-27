package com.example.starter.runway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 结构化幂等指纹：先将各部分（标量、集合、嵌套记录）序列化为无歧义 JSON 数组再取 SHA-256。
 * 字符串中的分隔符（如 "A|B"、引号、换行）由 JSON 转义区分；集合须由调用方先稳定排序后传入。
 */
@Component
public class FingerprintHasher {

    private final ObjectMapper objectMapper;

    public FingerprintHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 计算结构化指纹；parts 顺序与内容共同决定指纹，null 元素按 JSON null 编码。
     */
    public String hash(Object... parts) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(List.of(parts));
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(json);
            StringBuilder hex = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
