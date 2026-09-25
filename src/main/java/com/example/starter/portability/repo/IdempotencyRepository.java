package com.example.starter.portability.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.portability.IdempotencyRecord;

/**
 * 幂等记录表数据访问，主键 (endpoint, request_id)。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotencyRecord> find(String endpoint, String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE endpoint = ? AND request_id = ?",
                IdempotencyRepository::mapRow, endpoint, requestId).stream().findFirst();
    }

    public void insert(String endpoint, String requestId, String fingerprint,
                       int responseStatus, String responseBody, Long flowId, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record (endpoint, request_id, fingerprint,"
                        + " response_status, response_body, flow_id, created_at) VALUES (?,?,?,?,?,?,?)",
                endpoint, requestId, fingerprint, responseStatus, responseBody, flowId,
                Timestamp.from(createdAt));
    }

    private static IdempotencyRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        long flowId = rs.getLong("flow_id");
        return new IdempotencyRecord(
                rs.getString("endpoint"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getInt("response_status"),
                rs.getString("response_body"),
                rs.wasNull() ? null : flowId,
                rs.getTimestamp("created_at").toInstant());
    }
}
