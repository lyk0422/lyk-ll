package com.example.starter.rolling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：指纹覆盖 requestId 与全部业务字段，使用规范化 JSON 的 SHA-256。
 */
public final class FingerprintUtil {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private FingerprintUtil() {
    }

    /**
     * 计算业务请求对象的指纹（SHA-256，64 位十六进制）。
     */
    public static String fingerprint(Object request) {
        try {
            byte[] json = CANONICAL.writeValueAsBytes(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算请求指纹", e);
        }
    }

    /**
     * 将响应对象序列化为 JSON，用于幂等重放。
     */
    public static String toJson(Object response) {
        try {
            return CANONICAL.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化响应", e);
        }
    }

    /**
     * 反序列化幂等记录中保存的首次响应。
     */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return CANONICAL.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取首次响应", e);
        }
    }
}
