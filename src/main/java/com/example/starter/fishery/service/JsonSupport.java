package com.example.starter.fishery.service;

import com.example.starter.fishery.api.ConflictException;
import com.example.starter.fishery.api.dto.ReplayAware;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹与响应快照的 JSON 支持（调剂/结转服务共用）。
 */
final class JsonSupport {

    private JsonSupport() {
    }

    /** 规范化业务字段后计算 SHA-256 十六进制指纹。 */
    static String fingerprint(ObjectMapper objectMapper, Object canonicalPayload) {
        try {
            String json = objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(canonicalPayload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("生成幂等指纹失败", ex);
        }
    }

    /** 序列化首次成功结果快照。 */
    static String toJson(ObjectMapper objectMapper, Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化响应快照失败", ex);
        }
    }

    /** 读取首次响应快照并标记重放。 */
    static <T> T replay(ObjectMapper objectMapper, String json, Class<T> type) {
        if (json == null) {
            throw new ConflictException("首次请求尚未处理完成");
        }
        try {
            T value = objectMapper.readValue(json, type);
            if (value instanceof ReplayAware replayAware) {
                @SuppressWarnings("unchecked")
                T marked = (T) replayAware.withReplayed(true);
                return marked;
            }
            return value;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("读取首次响应快照失败", ex);
        }
    }
}
