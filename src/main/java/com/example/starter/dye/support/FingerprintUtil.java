package com.example.starter.dye.support;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹：操作类型 + requestId + 全部业务字段（规范化 JSON）的 SHA-256。
 * 同键同参指纹相同；任一字段变化即产生不同指纹，用于判定 409 冲突。
 * BigDecimal 统一去尾零（0.80 与 0.8 视为同参）。
 */
public final class FingerprintUtil {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

    static {
        SimpleModule module = new SimpleModule();
        module.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeNumber(value.stripTrailingZeros().toPlainString());
            }
        });
        CANONICAL_MAPPER.registerModule(module);
    }

    private FingerprintUtil() {
    }

    /**
     * 计算指纹。operation 与 requestId 显式前置，业务字段按规范化 JSON 追加。
     */
    public static String fingerprint(String operation, String requestId, Object payload) {
        String raw = operation + "|" + requestId + "|" + canonicalJson(payload);
        return sha256(raw);
    }

    public static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 对业务载荷做规范化序列化；record 组件按声明顺序输出，结果确定。
     */
    public static String canonicalJson(Object payload) {
        try {
            return CANONICAL_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法序列化幂等载荷", e);
        }
    }
}
