package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 写操作幂等记录数据访问；request_id 为主键，天然防重。
 */
@Repository
public class IdempotencyRepository {

    /** 幂等记录行。 */
    public record IdempotencyRow(String requestId, String fingerprint, String action,
            Long planId, String responseBody, LocalDateTime createdAt) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotencyRow> findByRequestId(String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT * FROM idempotency_record WHERE request_id = ?",
                (rs, rowNum) -> new IdempotencyRow(
                        rs.getString("request_id"),
                        rs.getString("fingerprint"),
                        rs.getString("action"),
                        rs.getObject("plan_id", Long.class),
                        rs.getString("response_body"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                requestId);
        return rows.stream().findFirst();
    }

    public void insert(String requestId, String fingerprint, String action, Long planId,
            String responseBody, LocalDateTime createdAt) {
        jdbc.update(
                "INSERT INTO idempotency_record (request_id, fingerprint, action, plan_id, response_body, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                requestId, fingerprint, action, planId, responseBody, Timestamp.valueOf(createdAt));
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM idempotency_record");
    }
}
