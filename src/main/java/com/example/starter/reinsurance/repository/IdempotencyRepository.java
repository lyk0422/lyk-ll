package com.example.starter.reinsurance.repository;

import com.example.starter.reinsurance.domain.IdempotencyRecord;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * requestId 幂等记录仓储。主键即 requestId，插入冲突表示同键并发请求已先行落库。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, n) -> {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setRequestId(rs.getString("request_id"));
        record.setOperation(rs.getString("operation"));
        record.setFingerprint(rs.getString("fingerprint"));
        record.setHttpStatus(rs.getInt("http_status"));
        record.setResultRef(rs.getString("result_ref"));
        record.setResultJson(rs.getString("result_json"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            record.setCreatedAt(createdAt.toInstant());
        }
        return record;
    };

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public IdempotencyRecord find(String requestId) {
        List<IdempotencyRecord> list = jdbcTemplate.query(
                "SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId);
        return list.stream().findFirst().orElse(null);
    }

    /**
     * 抢占式插入幂等记录（指纹占位）。
     *
     * @return 插入成功返回 {@code true}；同 requestId 已存在返回 {@code false}
     */
    public boolean tryInsertPlaceholder(String requestId, String operation, String fingerprint) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO idempotency_record (request_id, operation, fingerprint, http_status, "
                            + "result_ref, result_json, created_at) VALUES (?,?,?,?,NULL,NULL,?)",
                    requestId,
                    operation,
                    fingerprint,
                    0,
                    Timestamp.from(Instant.now(clock)));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 业务完成后回填首次结果。 */
    public void complete(
            String requestId, int httpStatus, String resultRef, String resultJson) {
        jdbcTemplate.update(
                "UPDATE idempotency_record SET http_status = ?, result_ref = ?, result_json = ? "
                        + "WHERE request_id = ?",
                httpStatus,
                resultRef,
                resultJson,
                requestId);
    }
}
