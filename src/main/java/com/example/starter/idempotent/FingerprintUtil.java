package com.example.starter.idempotent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等指纹：SHA-256 覆盖 requestId 与全部业务字段。
 *
 * <p>规范化编码为<b>自描述长度分帧</b>结构：每个字段带类型标记与 UTF-8 字节长度，
 * 字段名按字典序排列。因此：
 * <ul>
 *   <li>合法文本即使包含分隔符（如 {@code |}、{@code "}、换行）也不会与其它字段混淆；</li>
 *   <li>数值按 {@link BigDecimal} 规范值比较，{@code 20}、{@code 20.0}、{@code 20.000}
 *       指纹相同；</li>
 *   <li>对象字段顺序不同但结构相同的报文指纹相同，多/少字段或拼接歧义指纹不同。</li>
 * </ul>
 */
@Component
public class FingerprintUtil {

    private final ObjectMapper objectMapper;

    public FingerprintUtil(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String fingerprint(String requestId, Object payload) {
        try {
            JsonNode root = objectMapper.valueToTree(payload);
            String canonical = "R" + frame(requestId) + encode(root);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("fingerprint computation failed", e);
        }
    }

    /**
     * 递归编码为「类型 + 长度 + 内容」的无歧义文本。
     */
    private String encode(JsonNode node) {
        if (node == null || node.isNull()) {
            return "Z" + frame("");
        }
        if (node.isObject()) {
            // 字段名排序，逐键长度分帧，杜绝字段顺序与分隔符歧义
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                sorted.put(entry.getKey(), entry.getValue());
            }
            StringBuilder sb = new StringBuilder("O").append(frame(Integer.toString(sorted.size())));
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                sb.append(frame(entry.getKey())).append(encode(entry.getValue()));
            }
            return sb.toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("A").append(frame(Integer.toString(node.size())));
            for (JsonNode item : node) {
                sb.append(encode(item));
            }
            return sb.toString();
        }
        if (node.isNumber()) {
            // 规范数值：去尾零、纯小数文本，整数/小数等价文本同指纹
            BigDecimal decimal = node.decimalValue().stripTrailingZeros();
            if (decimal.signum() == 0) {
                decimal = BigDecimal.ZERO;
            }
            return "N" + frame(decimal.toPlainString());
        }
        if (node.isBoolean()) {
            return "B" + frame(Boolean.toString(node.booleanValue()));
        }
        // 文本：长度分帧，内容中的任何分隔符都不影响边界
        return "S" + frame(node.asText());
    }

    /**
     * 长度分帧：{@code <字节长度>:<内容>}，例如 {@code 5:hello}。
     */
    private String frame(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length + ":" + value;
    }
}
