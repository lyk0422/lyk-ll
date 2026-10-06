package com.example.starter.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等记录数据访问。(request_id, action) 唯一；fingerprint 覆盖全部业务字段。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 幂等记录行。
     */
    public record IdempotencyRow(long id, String requestId, String action, String fingerprint,
            String responseBody, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotencyRow> find(String requestId, String action) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT id, request_id, action, fingerprint, response_body, created_at"
                        + " FROM idempotency_record WHERE request_id = ? AND action = ?",
                (rs, n) -> new IdempotencyRow(
                        rs.getLong("id"),
                        rs.getString("request_id"),
                        rs.getString("action"),
                        rs.getString("fingerprint"),
                        rs.getString("response_body"),
                        rs.getTimestamp("created_at").toInstant()),
                requestId, action);
        return rows.stream().findFirst();
    }

    public void insert(String requestId, String action, String fingerprint, String responseBody,
            Instant createdAt) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO idempotency_record (request_id, action, fingerprint, response_body, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, action);
            ps.setString(3, fingerprint);
            ps.setString(4, responseBody);
            ps.setTimestamp(5, Timestamp.from(createdAt));
            return ps;
        });
    }
}
