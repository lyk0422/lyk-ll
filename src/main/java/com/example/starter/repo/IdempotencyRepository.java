package com.example.starter.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 幂等记录数据访问；request_id 主键保证同键唯一。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 幂等记录行。
     *
     * @param requestId    幂等请求键
     * @param operation    操作类型
     * @param fingerprint  请求指纹 SHA-256
     * @param responseBody 首次成功响应 JSON 快照
     */
    public record IdempotencyRow(String requestId, String operation, String fingerprint, String responseBody) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按键查询幂等记录。
     */
    public Optional<IdempotencyRow> findByRequestId(String requestId) {
        return jdbc.query("SELECT request_id, operation, fingerprint, response_body"
                        + " FROM idempotency_record WHERE request_id = ?",
                (rs, n) -> new IdempotencyRow(rs.getString("request_id"), rs.getString("operation"),
                        rs.getString("fingerprint"), rs.getString("response_body")),
                requestId).stream().findFirst();
    }

    /**
     * 写入幂等记录；同键并发写入时仅一条成功。
     *
     * @throws DuplicateKeyException 同键已存在
     */
    public void insert(String requestId, String operation, String fingerprint, String responseBody) {
        jdbc.update("INSERT INTO idempotency_record (request_id, operation, fingerprint, response_body, created_at)"
                + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)", requestId, operation, fingerprint, responseBody);
    }

    /**
     * 更新响应快照（同事务内先占键后写快照）。
     */
    public void updateResponseBody(String requestId, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }
}
