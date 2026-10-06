package com.example.starter.repo;

import com.example.starter.model.IdempotencyRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 幂等记录数据访问。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, n) -> new IdempotencyRecord(
            rs.getString("request_id"), rs.getString("endpoint"), rs.getString("fingerprint"),
            rs.getObject("response_status") == null ? null : rs.getInt("response_status"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入处理中占位记录（同事务内与业务写入一起提交或回滚）。
     */
    public void insertPending(String requestId, String endpoint, String fingerprint) {
        jdbc.update("INSERT INTO idempotency_record (request_id, endpoint, fingerprint) VALUES (?, ?, ?)",
                requestId, endpoint, fingerprint);
    }

    /**
     * 回填首次响应。
     */
    public void complete(String requestId, int status, String body) {
        jdbc.update("UPDATE idempotency_record SET response_status = ?, response_body = ?, "
                + "completed_at = CURRENT_TIMESTAMP WHERE request_id = ?", status, body, requestId);
    }

    /**
     * 按请求标识查询。
     */
    public Optional<IdempotencyRecord> findByRequestId(String requestId) {
        return jdbc.query(
                        "SELECT request_id, endpoint, fingerprint, response_status, response_body "
                                + "FROM idempotency_record WHERE request_id = ?",
                        MAPPER, requestId)
                .stream().findFirst();
    }
}
