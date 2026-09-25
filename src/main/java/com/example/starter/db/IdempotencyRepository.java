package com.example.starter.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 幂等请求台账数据访问。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotentRequestPo> MAPPER = (rs, n) -> new IdempotentRequestPo(
            rs.getString("request_id"),
            rs.getString("kind"),
            rs.getString("fingerprint"),
            (Long) rs.getObject("silo_id"),
            rs.getInt("status_code"),
            rs.getString("response"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotentRequestPo> find(String requestId) {
        List<IdempotentRequestPo> rows = jdbc.query(
                "SELECT * FROM idempotent_request WHERE request_id = ?", MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public void insert(String requestId, String kind, String fingerprint, Long siloId, int statusCode,
                       String response, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO idempotent_request (request_id, kind, fingerprint, silo_id, status_code, "
                        + "response, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                requestId, kind, fingerprint, siloId, statusCode, response, Timestamp.valueOf(createdAt));
    }
}
