package com.example.starter.roaming.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * requestId 幂等登记：指纹覆盖 requestId 与全部业务字段。
 */
@Repository
public class IdempotentRequestRepository {

    private final JdbcTemplate jdbc;

    public IdempotentRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等登记行；httpStatus 为 0/响应体为空表示处理中（同事务内登记，失败随事务回滚）。 */
    public record RequestRow(String requestId, String requestType, String fingerprint,
                             Integer httpStatus, String responseBody, Instant createdAt) {
    }

    public Optional<RequestRow> find(String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT request_id, request_type, fingerprint, http_status, response_body, created_at_utc "
                            + "FROM idempotent_request WHERE request_id = ?",
                    (rs, n) -> new RequestRow(
                            rs.getString("request_id"),
                            rs.getString("request_type"),
                            rs.getString("fingerprint"),
                            rs.getInt("http_status"),
                            rs.getString("response_body"),
                            rs.getTimestamp("created_at_utc").toInstant()),
                    requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 先占用 requestId（同事务内），并发同键由主键裁决。 */
    public void insertPending(String requestId, String requestType, String fingerprint, Instant now) {
        jdbc.update("INSERT INTO idempotent_request (request_id, request_type, fingerprint, http_status, response_body, created_at_utc) "
                        + "VALUES (?, ?, ?, 0, NULL, ?)",
                requestId, requestType, fingerprint, Timestamp.from(now));
    }

    /** 业务处理成功后回填首次响应原文与状态码。 */
    public void complete(String requestId, int httpStatus, String responseBody) {
        jdbc.update("UPDATE idempotent_request SET http_status = ?, response_body = ? WHERE request_id = ?",
                httpStatus, responseBody, requestId);
    }
}
