package com.example.starter.calibration.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.starter.calibration.service.ValidationException;

import java.util.List;

/**
 * JSON 序列化辅助，统一异常为 422。
 */
public final class JsonSupport {

    private JsonSupport() {
    }

    public static String write(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ValidationException("参数内容不是合法 JSON: " + e.getOriginalMessage());
        }
    }

    public static List<String> readStringList(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            throw new ValidationException("车辆/传感器范围 JSON 解析失败: " + e.getOriginalMessage());
        }
    }
}
