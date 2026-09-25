package com.example.starter.fermentation.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 幂等受理记录数据访问。request_id 全局唯一，并发首次受理由唯一约束兜底。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 幂等受理记录。
     *
     * @param endpoint       首次受理的接口路径
     * @param fingerprint    请求指纹（requestId 与全部业务字段的 SHA-256）
     * @param responseStatus 首次受理返回的 HTTP 状态码
     * @param responseBody   首次受理返回的响应体（JSON）
     */
    public record IdempotencyRecord(String endpoint, String fingerprint, int responseStatus,
                                    String responseBody) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotencyRecord> findByRequestId(String requestId) {
        return jdbc.query(
                "SELECT endpoint, fingerprint, response_status, response_body FROM idempotency_record"
                        + " WHERE request_id = ?",
                rs -> rs.next()
                        ? Optional.of(new IdempotencyRecord(rs.getString(1), rs.getString(2),
                                rs.getInt(3), rs.getString(4)))
                        : Optional.empty(),
                requestId);
    }

    public void insert(String requestId, String endpoint, String fingerprint,
                       int responseStatus, String responseBody, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO idempotency_record"
                        + " (request_id, endpoint, fingerprint, response_status, response_body, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                requestId, endpoint, fingerprint, responseStatus, responseBody,
                Timestamp.valueOf(createdAt));
    }
}
