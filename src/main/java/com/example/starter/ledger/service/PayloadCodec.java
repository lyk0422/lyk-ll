package com.example.starter.ledger.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.TreeMap;

/**
 * 事件载荷的规范化 JSON 编解码（字段按字典序排序，保证哈希可复算）。
 */
public final class PayloadCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PayloadCodec() {
    }

    /** 序列化为规范化 JSON 字符串。 */
    public static String encode(Map<String, ?> payload) {
        try {
            TreeMap<String, Object> sorted = new TreeMap<>();
            if (payload != null) {
                sorted.putAll(payload);
            }
            return MAPPER.writeValueAsString(sorted);
        } catch (Exception e) {
            throw new IllegalStateException("事件载荷序列化失败", e);
        }
    }

    /** 反序列化为有序 Map 用于复算哈希。 */
    public static Map<String, Object> decode(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<TreeMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("事件载荷反序列化失败: " + json, e);
        }
    }
}
