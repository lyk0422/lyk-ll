package com.example.starter.epidemic.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 请求指纹工具：对包含 requestId 的规范化 JSON 做 SHA-256，用于同键异参冲突判定。
 */
@Component
public class Fingerprinter {

    private final ObjectMapper objectMapper;

    public Fingerprinter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 计算业务参数指纹（字段按排序后序列化，保证稳定）。
     */
    public String fingerprint(Object request) {
        try {
            byte[] canonical = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical);
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }

    /** 序列化为 JSON 字符串，失败抛出非法状态异常。 */
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** 由 JSON 反序列化，失败抛出非法状态异常。 */
    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json.getBytes(StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }
}
