package com.example.starter.trace.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 幂等记录的数据访问：request_id 为主键，天然防并发重复。
 */
@Repository
public class IdempotencyRepository {

    /** 幂等记录行。 */
    public record IdempotencyRow(String requestId, String fingerprint, int httpStatus, String responseBody) {
    }

    private static final RowMapper<IdempotencyRow> MAPPER = (rs, n) -> new IdempotencyRow(
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getInt("http_status"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求 ID 查询幂等记录。
     */
    public Optional<IdempotencyRow> findByRequestId(String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT request_id, fingerprint, http_status, response_body FROM idempotency_record WHERE request_id = ?",
                MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 写入幂等记录；主键冲突时抛出 {@link DuplicateKeyException}。
     */
    public void insert(String requestId, String fingerprint, int httpStatus, String responseBody,
                       LocalDateTime createdAt) {
        jdbc.update("INSERT INTO idempotency_record (request_id, fingerprint, http_status, response_body, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                requestId, fingerprint, httpStatus, responseBody, Timestamp.valueOf(createdAt));
    }
}
