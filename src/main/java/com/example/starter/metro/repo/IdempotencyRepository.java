package com.example.starter.metro.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等键数据访问：requestId 主键占位，完成时写入首次响应报文。
 */
@Repository
public class IdempotencyRepository {

    /** 幂等记录。 */
    public record IdempotencyRow(String requestId, String fingerprint, String status,
            String responseBody) {
    }

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<IdempotencyRow> find(String requestId) {
        List<IdempotencyRow> rows = jdbcTemplate.query(
                "SELECT request_id, fingerprint, status, response_body FROM idempotency_record"
                        + " WHERE request_id = ?",
                (rs, rowNum) -> new IdempotencyRow(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)),
                requestId);
        return rows.stream().findFirst();
    }

    /**
     * 占位声明；requestId 已存在时抛出 DuplicateKeyException。
     */
    public void insertClaim(String requestId, String fingerprint, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO idempotency_record(request_id, fingerprint, status, created_at)"
                        + " VALUES (?,?,?,?)",
                requestId, fingerprint, STATUS_IN_PROGRESS, Timestamp.valueOf(now));
    }

    public void markCompleted(String requestId, String responseBody, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE idempotency_record SET status = ?, response_body = ?, completed_at = ?"
                        + " WHERE request_id = ?",
                STATUS_COMPLETED, responseBody, Timestamp.valueOf(now), requestId);
    }

    /**
     * 业务失败时释放占位，允许调用方修正后重试。
     */
    public void delete(String requestId) {
        jdbcTemplate.update("DELETE FROM idempotency_record WHERE request_id = ?", requestId);
    }
}
