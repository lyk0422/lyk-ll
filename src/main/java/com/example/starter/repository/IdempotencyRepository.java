package com.example.starter.repository;

import com.example.starter.domain.IdempotencyRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 新增写操作幂等台账数据访问：与业务数据同事务写入，失败不占键。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, n) -> new IdempotencyRecord(
            rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getString("operation"), rs.getString("result_snapshot"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查询。
     */
    public Optional<IdempotencyRecord> findByRequestId(String requestId) {
        return jdbc.query("SELECT request_id, fingerprint, operation, result_snapshot, created_at"
                + " FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 按幂等键查询并加写锁，持锁后重查以串行化同键并发。
     */
    public Optional<IdempotencyRecord> lockByRequestId(String requestId) {
        return jdbc.query("SELECT request_id, fingerprint, operation, result_snapshot, created_at"
                + " FROM idempotency_record WHERE request_id = ? FOR UPDATE", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 写入幂等记录（与业务变更同事务）。
     */
    public void insert(IdempotencyRecord record) {
        jdbc.update("INSERT INTO idempotency_record"
                        + " (request_id, fingerprint, operation, result_snapshot, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                record.requestId(), record.fingerprint(), record.operation(),
                record.resultSnapshot(), Timestamp.from(record.createdAt()));
    }
}
