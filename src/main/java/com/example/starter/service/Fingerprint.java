package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * 业务指纹：对 requestId 与全部业务字段的规范化 JSON 取 SHA-256。
 * 字段按名字排序，BigDecimal 入参由调用方规范化；同键同参指纹一致，任意业务字段不同即冲突。
 */
public final class Fingerprint {

    private static final JsonMapper CANONICAL_MAPPER = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    private Fingerprint() {
    }

    /** 计算请求对象的业务指纹。 */
    public static String of(Object request) {
        try {
            String canonical = CANONICAL_MAPPER.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("计算业务指纹失败", e);
        }
    }
}
