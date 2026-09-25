package com.example.starter.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.starter.support.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * requestId 幂等支撑：指纹覆盖 requestId 与全部业务字段。
 * 先在同一事务内抢占 idempotent_request 唯一行，再执行业务，最后回写首次响应。
 * 同键同参返回首次结果（含首次状态码）；同键异参返回 409 且不执行任何业务动作（不扣库存）。
 */
@Component
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在业务事务内执行幂等操作（调用方须处于事务中）。
     *
     * @param requestId 幂等键
     * @param operation 操作类型（COLOR_CHECK/SCHEDULE/MERGE/FEED/JUDGE）
     * @param payload   全部业务字段（不含 requestId 本身，指纹仍会覆盖它）
     * @param action    首次执行的业务动作
     * @return 首次结果或历史重放结果（replayed=true 表示重放）
     */
    public ServiceResult execute(String requestId, String operation, Map<String, Object> payload,
                                 java.util.function.Supplier<ServiceResult> action) {
        String fingerprint = fingerprint(operation, requestId, payload);

        try {
            // 抢占幂等键：唯一索引保证并发时只有一个事务能插入；冲突方在 H2 行锁上等待胜者提交/回滚。
            jdbc.update("INSERT INTO idempotent_request "
                            + "(request_id, operation, fingerprint, response_status, response_body, created_at) "
                            + "VALUES (?, ?, ?, 0, NULL, ?)",
                    requestId, operation, fingerprint, Timestamp.from(Instant.now(clock)));
        } catch (DuplicateKeyException duplicate) {
            Map<String, Object> winner = readRecord(requestId);
            if (winner == null) {
                throw new IllegalStateException("幂等键抢占失败但查无记录: " + requestId);
            }
            if (!fingerprint.equals(winner.get("fingerprint"))) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "requestId=" + requestId + " 已用于不同业务参数，拒绝处理（409 幂等冲突）");
            }
            return new ServiceResult((Integer) winner.get("status"),
                    readBody((String) winner.get("body")), true);
        }

        ServiceResult result = action.get();
        jdbc.update("UPDATE idempotent_request SET response_status = ?, response_body = ? WHERE request_id = ?",
                result.status(), writeBody(result.body()), requestId);
        return result;
    }

    private Map<String, Object> readRecord(String requestId) {
        return jdbc.query(
                "SELECT fingerprint, response_status, response_body FROM idempotent_request WHERE request_id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> row = new HashMap<>();
                    row.put("fingerprint", rs.getString("fingerprint"));
                    row.put("status", rs.getInt("response_status"));
                    row.put("body", rs.getString("response_body"));
                    return row;
                },
                requestId);
    }

    /**
     * 计算指纹：operation 与 requestId 在内，业务参数递归排序键并规范化数值文本。
     */
    public String fingerprint(String operation, String requestId, Map<String, Object> payload) {
        TreeMap<String, Object> canonical = new TreeMap<>();
        canonical.put("operation", operation);
        canonical.put("requestId", requestId);
        canonical.put("payload", normalize(payload));
        try {
            String json = objectMapper.writeValueAsString(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), normalize(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::normalize).toList();
        }
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String writeBody(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }
}
