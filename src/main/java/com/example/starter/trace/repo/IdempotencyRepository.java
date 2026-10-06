package com.example.starter.trace.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 写操作幂等记录的数据访问。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试写入幂等记录。
     *
     * @return true 首次写入；false requestId 已存在
     */
    public boolean tryInsert(String requestId, String fingerprint, String operation,
                             int responseStatus, String responseBody, LocalDateTime createdAt) {
        try {
            jdbc.update(
                    "INSERT INTO idempotency_record (request_id, fingerprint, operation, response_status, response_body, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    requestId, fingerprint, operation, responseStatus, responseBody,
                    Timestamp.valueOf(createdAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 回写首次受理的响应状态与响应体。
     */
    public void updateResponse(String requestId, int responseStatus, String responseBody) {
        jdbc.update(
                "UPDATE idempotency_record SET response_status = ?, response_body = ? WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }

    /**
     * 按 requestId 查询幂等记录。
     */
    public Optional<IdempotencyRow> findByRequestId(String requestId) {
        return jdbc.query(
                        "SELECT request_id, fingerprint, operation, response_status, response_body, created_at"
                                + " FROM idempotency_record WHERE request_id = ?",
                        (rs, i) -> new IdempotencyRow(rs.getString("request_id"), rs.getString("fingerprint"),
                                rs.getString("operation"), rs.getInt("response_status"),
                                rs.getString("response_body"),
                                rs.getTimestamp("created_at").toLocalDateTime()),
                        requestId)
                .stream().findFirst();
    }

    /**
     * 幂等记录行。
     */
    public record IdempotencyRow(String requestId, String fingerprint, String operation,
                                 Integer responseStatus, String responseBody, LocalDateTime createdAt) {
    }
}
