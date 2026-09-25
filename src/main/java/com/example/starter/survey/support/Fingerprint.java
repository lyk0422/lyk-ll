package com.example.starter.survey.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务指纹：覆盖 requestId、操作类型与全部业务字段。
 * 以字段名字典序规范化后做 SHA-256；同键同参指纹一致，任一参数变化指纹不同。
 */
public final class Fingerprint {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    private Fingerprint() {
    }

    /** 按有序键值对计算指纹，键将按名称排序。 */
    public static String of(String requestId, String operation, Map<String, ?> fields) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("requestId", requestId);
        all.put("operation", operation);
        all.put("fields", fields);
        try {
            String json = CANONICAL.writeValueAsString(all);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }
}
