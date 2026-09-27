package com.example.starter.idempotent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * 幂等指纹：SHA-256 覆盖 requestId 与全部业务字段。
 *
 * <p>指纹串为结构化规范化形式：对象字段按名排序、数组保序、字符串整体 JSON 转义引用，
 * 因此合法文本中即使含有分隔符（如 {@code |}、{@code ,}、{@code :}）也能与结构边界无歧义区分；
 * 数值统一按规范值输出（去尾零、非科学计数），整数与小数文本等价（如 10、10.0、10.000）
 * 得到同一指纹。
 */
@Component
public class FingerprintUtil {

    private final ObjectMapper objectMapper;

    public FingerprintUtil(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String fingerprint(String requestId, Object payload) {
        try {
            StringBuilder canonical = new StringBuilder();
            appendCanonical(objectMapper.valueToTree(payload), canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (quote(requestId) + "|" + canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("fingerprint computation failed", e);
        }
    }

    private void appendCanonical(JsonNode node, StringBuilder sb) throws JsonProcessingException {
        if (node == null || node.isNull()) {
            sb.append("null");
        } else if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            sb.append('{');
            boolean first = true;
            for (String name : names) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(name)).append(':');
                appendCanonical(node.get(name), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendCanonical(node.get(i), sb);
            }
            sb.append(']');
        } else if (node.isNumber()) {
            sb.append(canonicalNumber(node.decimalValue()));
        } else if (node.isTextual()) {
            sb.append(quote(node.textValue()));
        } else if (node.isBoolean()) {
            sb.append(node.booleanValue());
        } else {
            sb.append(objectMapper.writeValueAsString(node));
        }
    }

    /**
     * 数值规范值：去尾零后按普通十进制输出，0 的任何小数写法统一为 0。
     */
    private String canonicalNumber(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.signum() == 0) {
            stripped = BigDecimal.ZERO;
        }
        return stripped.toPlainString();
    }

    private String quote(String text) throws JsonProcessingException {
        return objectMapper.writeValueAsString(text);
    }
}
