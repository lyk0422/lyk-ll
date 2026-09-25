package com.example.starter.wb.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹：SHA-256(requestId + 规范化 JSON 业务字段)。
 * 业务入参均为 record，字段顺序固定；Map 字段按键排序，保证同参同指纹。
 */
public final class FingerprintUtil {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private FingerprintUtil() {
    }

    public static String sha256(String requestId, Object payload, ObjectMapper objectMapper) {
        try {
            String canonical = objectMapper.copy()
                    .findAndRegisterModules()
                    .writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(payload);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((requestId + "|" + canonical).getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
