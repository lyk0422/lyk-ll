package com.example.starter.rt.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

import com.example.starter.rt.domain.OperationType;

/**
 * 幂等指纹工具：指纹覆盖 requestId、操作类型与全部业务字段。
 * 业务字段按 Key 字典序排序后参与 SHA-256，保证参数顺序不同不影响指纹、任一字段不同指纹必然不同。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 计算指纹。
     *
     * @param operation 操作类型
     * @param requestId 幂等键
     * @param params    全部业务字段（值允许为 null，按字面量 "null" 参与）
     * @return 64 位十六进制 SHA-256 摘要
     */
    public static String of(OperationType operation, String requestId, Map<String, ?> params) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        if (params != null) {
            sorted.putAll(params);
        }
        StringBuilder plain = new StringBuilder();
        plain.append(operation.name()).append('\n').append(requestId).append('\n');
        sorted.forEach((key, value) -> plain.append(key).append('=')
                .append(value == null ? "null" : value.toString()).append('\n'));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(plain.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
