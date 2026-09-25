package com.example.starter.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 幂等受理：同键同参返回首次结果快照，同键异参返回 409 且不产生任何新版本/新数据。
 * 先占位后执行：并发同键仅一方执行业务动作，另一方重放首次结果；
 * 受理记录与业务写入同事务提交，失败整体回滚。
 */
@Service
public class IdempotencyService {

    /** 幂等处理结果：HTTP 状态与响应报文快照。 */
    public record Outcome(int httpStatus, String bodyJson, boolean replayed) {
    }

    private record StoredRow(String fingerprint, int httpStatus, String bodyJson) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 在事务内执行幂等动作。
     *
     * @param requestId   幂等请求号
     * @param fingerprint 覆盖 requestId 与全部业务字段的指纹
     * @param freshStatus 首次成功时的 HTTP 状态码
     * @param action      业务动作，返回将被序列化为响应报文的对象
     */
    @Transactional
    public Outcome run(String requestId, String fingerprint, int freshStatus, Supplier<Object> action) {
        StoredRow existing = find(requestId);
        if (existing != null) {
            return replay(requestId, fingerprint, existing);
        }
        try {
            // 先占位：并发同键时仅一方占位成功，另一方阻塞后重放
            jdbc.update(
                    "INSERT INTO idempotency_key (request_id, fingerprint, http_status, response_body, created_at)"
                            + " VALUES (?, ?, 0, '', ?)",
                    requestId, fingerprint, Timestamp.valueOf(LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            StoredRow raced = find(requestId);
            if (raced == null || raced.httpStatus() == 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "requestId " + requestId + " 正在处理中，请稍后重试");
            }
            return replay(requestId, fingerprint, raced);
        }
        Object body = action.get();
        String bodyJson = toJson(body);
        jdbc.update(
                "UPDATE idempotency_key SET http_status = ?, response_body = ? WHERE request_id = ?",
                freshStatus, bodyJson, requestId);
        return new Outcome(freshStatus, bodyJson, false);
    }

    private Outcome replay(String requestId, String fingerprint, StoredRow stored) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "requestId " + requestId + " 已受理且业务参数不一致，拒绝重放");
        }
        return new Outcome(stored.httpStatus(), stored.bodyJson(), true);
    }

    private StoredRow find(String requestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT fingerprint, http_status, response_body FROM idempotency_key WHERE request_id = ?",
                    (rs, n) -> new StoredRow(
                            rs.getString("fingerprint"), rs.getInt("http_status"),
                            rs.getString("response_body")),
                    requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
