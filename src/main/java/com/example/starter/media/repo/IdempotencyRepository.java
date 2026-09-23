package com.example.starter.media.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 写请求幂等记录：requestId 为键，指纹相同时回放首次结果，不同则冲突。
 *
 * <p>占键与业务写入在同一事务：先插入 PENDING 占位行阻止并发同名请求，
 * 业务成功后回填最终状态码与响应体；业务失败回滚时占位行一并回滚，
 * requestId 可被客户端安全重试。</p>
 */
@Repository
public class IdempotencyRepository {

    /** 占位响应体；仅存在于未提交事务中，对外不可见。 */
    public static final String PENDING_BODY = "PENDING";

    /**
     * 已落库的幂等记录。
     */
    public record RecordedRequest(String requestId, String operation, String fingerprint,
                                  int statusCode, String responseBody) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 占用 requestId 键。
     *
     * @return true 表示占键成功；false 表示同 requestId 已被已提交事务使用
     */
    public boolean acquire(String requestId, String operation, String fingerprint, Instant now) {
        try {
            jdbc.update("""
                            INSERT INTO idempotent_request (request_id, operation, fingerprint, status_code, response_body, created_at)
                            VALUES (?, ?, ?, 0, ?, ?)""",
                    requestId, operation, fingerprint, PENDING_BODY, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /**
     * 业务成功后回填首次结果。
     */
    public void complete(String requestId, int statusCode, String responseBody) {
        int updated = jdbc.update(
                "UPDATE idempotent_request SET status_code = ?, response_body = ? WHERE request_id = ?",
                statusCode, responseBody, requestId);
        if (updated != 1) {
            throw new IllegalStateException("幂等记录回填失败: " + requestId);
        }
    }

    public RecordedRequest find(String requestId) {
        List<RecordedRequest> rows = jdbc.query(
                "SELECT request_id, operation, fingerprint, status_code, response_body FROM idempotent_request WHERE request_id = ?",
                (rs, n) -> new RecordedRequest(
                        rs.getString("request_id"),
                        rs.getString("operation"),
                        rs.getString("fingerprint"),
                        rs.getInt("status_code"),
                        rs.getString("response_body")),
                requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
