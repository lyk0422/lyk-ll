package com.example.starter.waterlogging.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * requestId 幂等台账数据访问：(request_id, request_type) 唯一。
 */
@Repository
public class IdempotentRequestRepository {

    public IdempotentRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    protected final JdbcTemplate jdbcTemplate;

    /** 读取既有幂等记录。 */
    public Optional<StoredIdempotency> find(String requestId, String requestType) {
        List<StoredIdempotency> rows = jdbcTemplate.query("""
                        SELECT fingerprint, response_status, response_body
                        FROM idempotent_request
                        WHERE request_id = ? AND request_type = ?
                        """,
                (rs, n) -> new StoredIdempotency(
                        rs.getString("fingerprint"),
                        rs.getInt("response_status"),
                        rs.getString("response_body")),
                requestId, requestType);
        return rows.stream().findFirst();
    }

    /**
     * 保存首次结果；并发唯一受理由唯一约束拒绝负方。
     *
     * @return true 写入成功（首办）；false 键已存在（并发负方）
     */
    public boolean insertIfAbsent(String requestId, String requestType, String fingerprint,
                                  int responseStatus, String responseBody) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO idempotent_request
                                (request_id, request_type, fingerprint, response_status,
                                 response_body, created_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    requestId, requestType, fingerprint, responseStatus, responseBody,
                    Timestamp.valueOf(LocalDateTime.now()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 首办占坑：先以指纹登记“处理中”记录（response_status=0），抢占 requestId。
     * 业务动作随后在同一事务内完成并以 {@link #completeOutcome} 回填最终结果；
     * 动作失败时事务回滚，占坑与业务副作用一同消失。
     *
     * @return true 占坑成功；false 同键已被占用（含处理中）
     */
    public boolean insertPlaceholder(String requestId, String requestType, String fingerprint) {
        return insertIfAbsent(requestId, requestType, fingerprint, RESPONSE_PENDING, null);
    }

    /** 同事务回填首办最终结果（成功或确定性业务失败）。 */
    public int completeOutcome(String requestId, String requestType, int responseStatus,
                               String responseBody) {
        return jdbcTemplate.update("""
                        UPDATE idempotent_request SET response_status = ?, response_body = ?
                        WHERE request_id = ? AND request_type = ?
                        """,
                responseStatus, responseBody, requestId, requestType);
    }

    /** 处理中占位状态码；正常 HTTP 状态码均大于 0。 */
    public static final int RESPONSE_PENDING = 0;

    /** 幂等台账存储内容。 */
    public record StoredIdempotency(String fingerprint, int responseStatus, String responseBody) {
    }
}
