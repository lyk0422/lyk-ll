package com.example.starter.dimming;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等记录数据访问：requestId 全局唯一，指纹覆盖 requestId 与全部业务字段。
 */
@Repository
public class IdempotencyRepository {

    /** 幂等记录行 */
    public record IdempotencyRow(
            String requestId,
            String endpoint,
            String fingerprint,
            Integer httpStatus,
            String responseBody) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static IdempotencyRow map(ResultSet rs, int rowNum) throws SQLException {
        Integer status = rs.getObject("http_status", Integer.class);
        return new IdempotencyRow(
                rs.getString("request_id"),
                rs.getString("endpoint"),
                rs.getString("fingerprint"),
                status,
                rs.getString("response_body"));
    }

    public void insert(String requestId, String endpoint, String fingerprint, LocalDateTime createdAt) {
        jdbc.update(
                "INSERT INTO idempotency_record (request_id, endpoint, fingerprint, created_at) VALUES (?,?,?,?)",
                requestId, endpoint, fingerprint, createdAt);
    }

    public Optional<IdempotencyRow> findByRequestId(String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT * FROM idempotency_record WHERE request_id = ?",
                IdempotencyRepository::map, requestId);
        return rows.stream().findFirst();
    }

    public void complete(String requestId, int httpStatus, String responseBody) {
        jdbc.update(
                "UPDATE idempotency_record SET http_status = ?, response_body = ? WHERE request_id = ?",
                httpStatus, responseBody, requestId);
    }

    public void delete(String requestId) {
        jdbc.update("DELETE FROM idempotency_record WHERE request_id = ?", requestId);
    }
}
