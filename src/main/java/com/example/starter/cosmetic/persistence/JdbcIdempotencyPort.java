package com.example.starter.cosmetic.persistence;

import com.example.starter.cosmetic.port.IdempotencyPort;
import com.example.starter.cosmetic.port.IdempotencyRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的幂等记录适配器。
 *
 * <p>claim 使用普通 INSERT，主键冲突（含并发同键）由 Spring 转译为
 * DataIntegrityViolationException；该表 claim 唯一可能的约束冲突即 requestId 重复。</p>
 */
@Repository
public class JdbcIdempotencyPort implements IdempotencyPort {

    private static final RowMapper<IdempotencyRow> MAPPER = (rs, n) -> new IdempotencyRow(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("fingerprint"),
            rs.getInt("http_status"),
            rs.getString("outcome"),
            rs.getString("filing_no"),
            rs.getString("response_json"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IdempotencyRow> find(String requestId) {
        return jdbc.query("""
                SELECT request_id, operation, fingerprint, http_status, outcome,
                       filing_no, response_json, created_at_utc
                FROM idempotency_record WHERE request_id = ?
                """, MAPPER, requestId).stream().findFirst();
    }

    @Override
    public void claim(String requestId, String operation, String fingerprint) {
        jdbc.update("""
                INSERT INTO idempotency_record
                  (request_id, operation, fingerprint, http_status, outcome,
                   filing_no, response_json, created_at_utc)
                VALUES (?, ?, ?, 0, '', NULL, '', ?)
                """,
                requestId, operation, fingerprint, Timestamp.from(Instant.now()));
    }

    @Override
    public void complete(String requestId, int httpStatus, String outcome,
                         String filingNo, String responseJson, Instant now) {
        int updated = jdbc.update("""
                UPDATE idempotency_record
                SET http_status = ?, outcome = ?, filing_no = ?, response_json = ?
                WHERE request_id = ?
                """, httpStatus, outcome, filingNo, responseJson, requestId);
        if (updated != 1) {
            throw new IllegalStateException("幂等记录回填失败: " + requestId);
        }
    }
}
