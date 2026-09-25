package com.example.starter.cosmetic.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段。
 *
 * <p>规范化规则：Map 键按字典序排序（深层递归）；List 保持输入顺序，
 * 列表顺序本身亦属指纹内容。最终取规范化 JSON 的 SHA-256 十六进制摘要。</p>
 */
public final class FingerprintSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private FingerprintSupport() {
    }

    /** 计算业务字段指纹。 */
    public static String sha256(Map<String, Object> fields) {
        try {
            String canonical = MAPPER.writeValueAsString(sortDeep(fields));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sortDeep(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), sortDeep(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(item -> out.add(sortDeep(item)));
            return out;
        }
        return value;
    }
}
