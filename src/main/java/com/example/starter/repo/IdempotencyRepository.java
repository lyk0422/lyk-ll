package com.example.starter.repo;

import com.example.starter.domain.IdempotencyRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Instant;

/**
 * requestId 幂等记录存取。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setRequestId(rs.getString("request_id"));
        record.setOperation(rs.getString("operation"));
        record.setFingerprint(rs.getString("fingerprint"));
        record.setHttpStatus(rs.getInt("http_status"));
        record.setResponseJson(rs.getString("response_json"));
        Timestamp created = rs.getTimestamp("created_at");
        record.setCreatedAt(created == null ? null : created.toInstant());
        return record;
    };

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 requestId 查询首次记录，不存在返回 null。 */
    public IdempotencyRecord find(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst().orElse(null);
    }

    /**
     * 在业务事务内先插入幂等占位记录（状态码 0、空响应）。
     * 与业务变更同事务提交：并发同键事务在此处撞主键，须等待首事务提交后重放。
     */
    public void insertPlaceholder(String requestId, String operation, String fingerprint, Instant now) {
        jdbc.update("""
                        INSERT INTO idempotency_record
                            (request_id, operation, fingerprint, http_status, response_json, created_at)
                        VALUES (?, ?, ?, 0, '', ?)
                        """,
                requestId, operation, fingerprint, Timestamp.from(now));
    }

    /**
     * 业务执行完成后，在同一事务内将占位记录定稿为首次结果。
     */
    public void finalize(String requestId, int httpStatus, String responseJson) {
        jdbc.update("""
                        UPDATE idempotency_record
                        SET http_status = ?, response_json = ?
                        WHERE request_id = ?
                        """,
                httpStatus, responseJson, requestId);
    }
}
