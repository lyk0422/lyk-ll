package com.example.rebate.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 幂等指纹：SHA-256，覆盖操作类型、requestId 与全部业务字段的规范化 JSON。
 */
@Component
public class FingerprintService {

    private final ObjectMapper canonicalMapper;

    public FingerprintService(ObjectMapper objectMapper) {
        // 复制一份并开启键排序，保证同参数不同字段顺序产生相同指纹。
        this.canonicalMapper = objectMapper.copy()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    }

    /**
     * 计算指纹：{@code SHA-256(opType | requestId | 规范化业务字段 JSON)}。
     */
    public String fingerprint(String opType, String requestId, Object payload) {
        String canonical;
        try {
            canonical = canonicalMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
        String raw = opType + '|' + requestId + '|' + canonical;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 将响应序列化为 JSON 文本存储，供同键重放原样返回。
     */
    public String toJson(Object response) {
        try {
            return canonicalMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
