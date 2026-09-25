package com.example.starter.repo;

import com.example.starter.domain.IdempotencyRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 幂等受理记录数据访问。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRow> ROW_MAPPER = (rs, i) -> new IdempotencyRow(
            rs.getString("request_id"),
            rs.getString("endpoint"),
            rs.getString("fingerprint"),
            (Integer) rs.getObject("response_status"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 先占位受理(响应为空表示处理中); 同键并发时唯一约束冲突抛出 DuplicateKeyException。
     */
    public void insertPending(String requestId, String endpoint, String fingerprint) {
        jdbcTemplate.update(
                "INSERT INTO idempotency_record (request_id, endpoint, fingerprint)"
                        + " VALUES (?, ?, ?)",
                requestId, endpoint, fingerprint);
    }

    public Optional<IdempotencyRow> find(String requestId) {
        return jdbcTemplate.query(
                        "SELECT request_id, endpoint, fingerprint, response_status, response_body"
                                + " FROM idempotency_record WHERE request_id = ?",
                        ROW_MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 业务受理完成后回写首次响应。
     */
    public void complete(String requestId, int responseStatus, String responseBody) {
        jdbcTemplate.update(
                "UPDATE idempotency_record SET response_status = ?, response_body = ?"
                        + " WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }
}
