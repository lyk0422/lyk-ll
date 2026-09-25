package com.example.starter.cutover.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段做规范化 JSON 后取 SHA-256。
 * 同键同参指纹一致；任一字段变化指纹即变化，用于 409 异参冲突判定。
 */
public final class FingerprintUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private FingerprintUtil() {
    }

    /**
     * 计算指纹。
     *
     * @param parts 参与指纹的全部业务字段（含 requestId），按固定顺序传入
     * @return 64 位小写十六进制 SHA-256
     */
    public static String sha256(Object... parts) {
        try {
            String canonical = MAPPER.writeValueAsString(parts);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }
}
