package com.example.starter.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 业务指纹：对请求体做稳定 JSON 序列化后取 SHA-256，覆盖 requestId 之外的全部业务字段
 * （requestId 本身作为独立幂等键存储，同键异参凭此指纹判定 409）。
 */
@Component
public class FingerprintHasher {

    private final ObjectMapper objectMapper;

    public FingerprintHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String sha256(Object payload) {
        try {
            String canonical = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法生成业务指纹", e);
        }
    }
}
