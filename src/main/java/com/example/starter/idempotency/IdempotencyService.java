package com.example.starter.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import com.example.starter.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 幂等控制：以 (requestId, endpoint) 为键，指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果；同键异参返回 409 且不执行业务、不改变额度。
 * 业务事务开始时先插入占位记录，事务提交时写入首次响应；
 * 并发同键请求因主键冲突回滚后重读已提交记录并重放。
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算请求指纹：endpoint + requestId + 全部业务字段的规范化 JSON 的 SHA-256。
     */
    public String fingerprint(String endpoint, Object requestDto) {
        String canonical;
        try {
            canonical = objectMapper.writeValueAsString(requestDto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
        return sha256(endpoint + '|' + canonical);
    }

    /**
     * 在业务事务内插入幂等占位记录；同键已存在时抛出 {@link DuplicateKeyException} 使事务回滚。
     */
    public void begin(String endpoint, String requestId, String fingerprint) {
        jdbc.update(
                "INSERT INTO idempotency_record (request_id, endpoint, fingerprint, response_status, response_body)"
                        + " VALUES (?, ?, ?, 0, '')",
                requestId, endpoint, fingerprint);
    }

    /**
     * 在业务事务内写入首次响应，随业务同事务提交。
     */
    public void complete(String endpoint, String requestId, int status, Object responseBody) {
        String body;
        try {
            body = objectMapper.writeValueAsString(responseBody);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
        jdbc.update(
                "UPDATE idempotency_record SET response_status = ?, response_body = ?"
                        + " WHERE request_id = ? AND endpoint = ?",
                status, body, requestId, endpoint);
    }

    /**
     * 读取已提交的幂等记录。
     */
    public Optional<StoredResponse> find(String endpoint, String requestId) {
        return jdbc.query(
                        "SELECT fingerprint, response_status, response_body FROM idempotency_record"
                                + " WHERE request_id = ? AND endpoint = ?",
                        (rs, rowNum) -> new StoredResponse(
                                rs.getString("fingerprint"),
                                rs.getInt("response_status"),
                                rs.getString("response_body")),
                        requestId, endpoint)
                .stream()
                .findFirst();
    }

    /**
     * 重放或拒绝：同参返回首次响应，异参抛 409。
     */
    public StoredResponse replayOrReject(String endpoint, Object requestDto, String requestId) {
        StoredResponse stored = find(endpoint, requestId)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_LOST", "幂等记录不存在，请重试"));
        if (!stored.fingerprint().equals(fingerprint(endpoint, requestDto))) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同requestId携带不同业务参数，拒绝执行且额度不变");
        }
        return stored;
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 已存储的首次响应。
     */
    public record StoredResponse(String fingerprint, int status, String body) {
    }
}
