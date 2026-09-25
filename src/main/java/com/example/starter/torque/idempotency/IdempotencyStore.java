package com.example.starter.torque.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * requestId 幂等支撑：
 * 指纹覆盖 requestId、请求类型、cardNo（路径业务字段）及全部请求业务字段（规范化 JSON 的 SHA-256）。
 * 同键同参返回首次结果；同键异参由调用方判定 409 且不改变工序状态。
 */
@Component
public class IdempotencyStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 已存储的首次响应。 */
    public record StoredResponse(String fingerprint, String responseJson, int httpStatus) {
    }

    public Optional<StoredResponse> find(String requestId, String requestType) {
        return jdbc.query("""
                        SELECT fingerprint, response_json, http_status
                        FROM idempotent_request WHERE request_id = ? AND request_type = ?
                        """,
                rs -> rs.next()
                        ? Optional.of(new StoredResponse(rs.getString("fingerprint"),
                        rs.getString("response_json"), rs.getInt("http_status")))
                        : Optional.empty(),
                requestId, requestType);
    }

    /**
     * 计算业务指纹。
     *
     * @param requestId   幂等键
     * @param requestType 请求类型
     * @param cardNo      路径上的工序卡编号（无则 null）
     * @param payload     全部业务字段（请求体）
     */
    public String fingerprint(String requestId, String requestType, String cardNo, Object payload) {
        try {
            Map<String, Object> root = new TreeMap<>();
            root.put("requestId", requestId);
            root.put("requestType", requestType);
            root.put("cardNo", cardNo == null ? "" : cardNo);
            JsonNode canonical = canonicalize(objectMapper.valueToTree(payload));
            root.put("payload", objectMapper.treeToValue(canonical, Object.class));
            String canonicalJson = objectMapper.writeValueAsString(root);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算幂等指纹失败", e);
        }
    }

    /**
     * 落盘首次响应；并发下唯一键兜底，插入失败返回 false 供调用方重读。
     */
    public boolean save(String requestId, String requestType, String fingerprint,
                        String responseJson, int httpStatus, Instant createdAt) {
        try {
            jdbc.update("""
                            INSERT INTO idempotent_request
                              (request_id, request_type, fingerprint, response_json, http_status, created_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    requestId, requestType, fingerprint, responseJson, httpStatus,
                    Timestamp.from(createdAt));
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 将 JSON 树中对象字段按字典序递归排序，保证相同业务内容指纹一致。
     */
    private static com.fasterxml.jackson.databind.JsonNode canonicalize(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode array =
                    OBJECT_MAPPER.createArrayNode();
            node.forEach(child -> array.add(canonicalize(child)));
            return array;
        }
        ObjectNode sorted = OBJECT_MAPPER.createObjectNode();
        new TreeMap<>(toMap((ObjectNode) node)).forEach((key, value) -> {
            if (value instanceof com.fasterxml.jackson.databind.JsonNode jsonNode) {
                sorted.set(key, canonicalize(jsonNode));
            }
        });
        return sorted;
    }

    private static Map<String, com.fasterxml.jackson.databind.JsonNode> toMap(ObjectNode node) {
        Map<String, com.fasterxml.jackson.databind.JsonNode> map = new TreeMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue()));
        return map;
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
}
