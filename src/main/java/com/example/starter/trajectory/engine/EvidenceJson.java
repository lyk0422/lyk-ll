package com.example.starter.trajectory.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 证据 JSON 序列化辅助。调用方使用 LinkedHashMap 固定字段顺序，
 * 保证相同证据永远产生相同文本。
 */
public final class EvidenceJson {

    private EvidenceJson() {
    }

    public static String write(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize evidence", e);
        }
    }
}
