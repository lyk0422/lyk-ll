package com.example.starter.pump.repo;

import com.example.starter.pump.domain.StoredRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 幂等请求台账仓储：先占位插入抢占 requestId，业务处理后回写首次结果。
 * 占位与业务写库处于同一事务，业务失败整体回滚，不会留下半成品占位。
 */
@Repository
public class IdempotentRepository {

    private final JdbcTemplate jdbc;

    public IdempotentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 requestId 查询首次受理记录。 */
    public Optional<StoredRequest> find(String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT request_id, biz_type, fingerprint, status_code, response_body, created_at
                    FROM idempotent_request WHERE request_id = ?
                    """,
                    (rs, n) -> new StoredRequest(
                            rs.getString("request_id"),
                            rs.getString("biz_type"),
                            rs.getString("fingerprint"),
                            rs.getObject("status_code", Integer.class),
                            rs.getString("response_body"),
                            rs.getObject("created_at", LocalDateTime.class)),
                    requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 占位插入抢占 requestId 主键。
     *
     * @return true 抢占成功；false 表示并发下已被其他事务占用
     */
    public boolean tryAcquire(String requestId, String bizType, String fingerprint,
                              LocalDateTime now) {
        try {
            jdbc.update(
                    """
                    INSERT INTO idempotent_request (request_id, biz_type, fingerprint,
                        status_code, response_body, created_at)
                    VALUES (?, ?, ?, NULL, NULL, ?)
                    """,
                    requestId, bizType, fingerprint, now);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    /** 回写首次受理结果。 */
    public void saveResult(String requestId, int statusCode, String responseBodyJson) {
        jdbc.update(
                "UPDATE idempotent_request SET status_code = ?, response_body = ? WHERE request_id = ?",
                statusCode, responseBodyJson, requestId);
    }
}
