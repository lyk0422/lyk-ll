package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * requestId 幂等记录。状态机：PROCESSING（首个请求独立事务占位）→ DONE（外事务提交后落首次结果）
 * / FAILED（外事务回滚后标记，允许同参重试）。指纹覆盖 requestId 与全部业务字段：
 * 同键同参返回首次结果，同键异参由上层判定为 409。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record IdempotentRecord(
            String requestId,
            String requestKind,
            String fingerprint,
            String status,
            Integer responseStatus,
            String responseBody,
            Instant createdAt,
            Instant finishedAt) {
    }

    private static final RowMapper<IdempotentRecord> MAPPER = (rs, n) -> {
        Integer statusCode = (Integer) rs.getObject("response_status");
        Timestamp finished = rs.getTimestamp("finished_at");
        return new IdempotentRecord(
                rs.getString("request_id"),
                rs.getString("request_kind"),
                rs.getString("fingerprint"),
                rs.getString("status"),
                statusCode,
                rs.getString("response_body"),
                rs.getTimestamp("created_at").toInstant(),
                finished == null ? null : finished.toInstant());
    };

    public Optional<IdempotentRecord> find(String requestId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT request_id, request_kind, fingerprint, status, response_status, response_body, "
                            + "created_at, finished_at FROM idempotent_request WHERE request_id = ?",
                    MAPPER, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 首个请求占位（调用方须用独立事务提交，使并发同键请求立即可见）。 */
    public void insertProcessing(String requestId, String requestKind, String fingerprint, Instant createdAt) {
        jdbcTemplate.update("INSERT INTO idempotent_request (request_id, request_kind, fingerprint, status, "
                        + "response_status, response_body, created_at, finished_at) "
                        + "VALUES (?, ?, ?, 'PROCESSING', NULL, NULL, ?, NULL)",
                requestId, requestKind, fingerprint, Timestamp.from(createdAt));
    }

    /** 业务外事务提交后，落首次结果。 */
    public void markDone(String requestId, int responseStatus, String responseBody, Instant finishedAt) {
        jdbcTemplate.update("UPDATE idempotent_request SET status = 'DONE', response_status = ?, "
                        + "response_body = ?, finished_at = ? WHERE request_id = ?",
                responseStatus, responseBody, Timestamp.from(finishedAt), requestId);
    }

    /** 业务外事务回滚后，释放占位允许同参重试。 */
    public void markFailed(String requestId, Instant finishedAt) {
        jdbcTemplate.update("UPDATE idempotent_request SET status = 'FAILED', finished_at = ? WHERE request_id = ?",
                Timestamp.from(finishedAt), requestId);
    }

    /**
     * 原子接管 FAILED 占位：仅当记录仍为 FAILED 时改为 PROCESSING，返回受影响行数。
     * 指纹在首次占位时即与 requestId 绑定，接管不改变指纹。
     */
    public int takeOverFailed(String requestId, Instant now) {
        return jdbcTemplate.update("UPDATE idempotent_request SET status = 'PROCESSING', "
                        + "response_status = NULL, response_body = NULL, created_at = ?, finished_at = NULL "
                        + "WHERE request_id = ? AND status = 'FAILED'",
                Timestamp.from(now), requestId);
    }

    public void delete(String requestId) {
        jdbcTemplate.update("DELETE FROM idempotent_request WHERE request_id = ?", requestId);
    }
}
