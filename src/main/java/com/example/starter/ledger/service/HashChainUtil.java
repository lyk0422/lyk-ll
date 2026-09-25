package com.example.starter.ledger.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * 哈希链与幂等指纹工具。事件哈希 = SHA-256(prevHash | 规范化字段串)，64 位小写十六进制。
 */
public final class HashChainUtil {

    /** 事件链起点的“上一事件哈希”：64 个 0。 */
    public static final String GENESIS_PREV_HASH = "0".repeat(64);

    private HashChainUtil() {
    }

    /**
     * 计算事件哈希。字段按 key 字典序排序后拼接，保证规范化、可复算。
     *
     * @param prevHash 上一事件哈希
     * @param fields   事件全部业务字段（含电池编码、类型、时间、业务参数）
     * @return 64 位小写十六进制 SHA-256
     */
    public static String eventHash(String prevHash, Map<String, ?> fields) {
        String canonical = canonicalize(fields);
        return sha256(prevHash + "|" + canonical);
    }

    /**
     * 计算幂等指纹，覆盖 requestId 与全部业务字段（操作类型也纳入，避免跨操作撞键）。
     */
    public static String fingerprint(String operation, String requestId, Map<String, ?> fields) {
        Map<String, Object> all = new TreeMap<>();
        if (fields != null) {
            all.putAll(fields);
        }
        all.put("__operation", nullToEmpty(operation));
        all.put("__requestId", nullToEmpty(requestId));
        return sha256(canonicalize(all));
    }

    private static String canonicalize(Map<String, ?> fields) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        if (fields != null) {
            sorted.putAll(fields);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            sb.append(entry.getKey()).append('=').append(nullToEmpty(entry.getValue())).append(';');
        }
        return sb.toString();
    }

    private static String nullToEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * 计算 SHA-256 摘要的 64 位小写十六进制表示。
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
