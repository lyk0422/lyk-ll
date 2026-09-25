package com.example.starter.museum;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等服务：以 requestId 为键，指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果（含首次错误结果），同键异参返回 409。
 */
@Service
public class IdempotencyService {

    /** 幂等执行结果：HTTP 状态码与响应体。 */
    public record ApiResult(int status, Map<String, Object> body) {
    }

    private static final String SEPARATOR = "";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, Clock clock, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算请求指纹：覆盖 requestId 与全部业务字段。
     *
     * @param parts requestId 及全部业务字段的字符串表示（null 以占位符参与）
     * @return SHA-256 十六进制指纹
     */
    public static String fingerprint(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            joined.append(part == null ? "<null>" : part).append(SEPARATOR);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(joined.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 幂等执行：首次执行并记录结果；同键同参重放返回首次结果；同键异参抛出 409。
     *
     * @param requestId   幂等请求号
     * @param opType      操作类型
     * @param fingerprint 请求指纹（须覆盖 requestId 与全部业务字段）
     * @param action      业务动作
     * @return 首次执行结果或其重放
     */
    public ApiResult execute(String requestId, String opType, String fingerprint, Supplier<ApiResult> action) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("requestId 不能为空");
        }
        try {
            jdbc.update(
                    "INSERT INTO idempotency_record (request_id, fingerprint, op_type, created_at) VALUES (?,?,?,?)",
                    requestId, fingerprint, opType, Instant.now(clock).toString());
        } catch (DuplicateKeyException e) {
            return replay(requestId, fingerprint);
        }
        try {
            ApiResult result = action.get();
            store(requestId, result.status(), result.body());
            return result;
        } catch (ApiException e) {
            store(requestId, e.status().value(), Map.of("error", e.getMessage(), "status", e.status().value()));
            throw e;
        } catch (RuntimeException e) {
            jdbc.update("DELETE FROM idempotency_record WHERE request_id = ?", requestId);
            throw e;
        }
    }

    private ApiResult replay(String requestId, String fingerprint) {
        Map<String, Object> record = jdbc.queryForMap(
                "SELECT fingerprint, response_status, response_body FROM idempotency_record WHERE request_id = ?",
                requestId);
        if (!fingerprint.equals(record.get("fingerprint"))) {
            throw ApiException.conflict("requestId 已使用且业务参数不一致");
        }
        Object status = record.get("response_status");
        if (status == null) {
            throw ApiException.conflict("相同 requestId 的请求正在处理中");
        }
        Map<String, Object> body = deserialize((String) record.get("response_body"));
        return new ApiResult(((Number) status).intValue(), body);
    }

    private void store(String requestId, int status, Map<String, Object> body) {
        jdbc.update("UPDATE idempotency_record SET response_status = ?, response_body = ? WHERE request_id = ?",
                status, serialize(body), requestId);
    }

    private String serialize(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private Map<String, Object> deserialize(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }
}
