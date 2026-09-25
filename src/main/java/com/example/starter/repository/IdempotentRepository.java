package com.example.starter.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 幂等请求记录访问。指纹覆盖 requestId 与全部业务字段；
 * 同键同参重放首次响应，同键异参由上层判 409。
 */
@Repository
public class IdempotentRepository {

    private final JdbcTemplate jdbc;

    public IdempotentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 首次登记幂等请求。
     *
     * @return true 首次插入成功；false requestId 已存在（需比对指纹）
     */
    public boolean insertIfAbsent(String requestId, String requestType, String fingerprint,
                                  int httpStatus, String responseBody, Instant now) {
        int rows = jdbc.update("""
                        INSERT INTO idempotent_request
                            (request_id, request_type, fingerprint, http_status, response_body, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                requestId, requestType, fingerprint, httpStatus, responseBody, Timestamp.from(now));
        return rows == 1;
    }

    /**
     * 插入 PROCESSING 占位行（状态码先置 0）。并发同键时唯一键冲突返回 false。
     */
    public boolean insertProcessing(String requestId, String requestType, String fingerprint, Instant now) {
        try {
            int rows = jdbc.update("""
                            INSERT INTO idempotent_request
                                (request_id, request_type, fingerprint, http_status, response_body, created_at)
                            VALUES (?, ?, ?, 0, 'PROCESSING', ?)
                            """,
                    requestId, requestType, fingerprint, Timestamp.from(now));
            return rows == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 业务执行成功后，用真实状态码与响应体替换占位行。 */
    public void finalizeRecord(String requestId, int httpStatus, String responseBody) {
        jdbc.update("UPDATE idempotent_request SET http_status = ?, response_body = ? WHERE request_id = ?",
                httpStatus, responseBody, requestId);
    }

    /** 删除幂等记录（系统错误回滚后清理占位行）。 */
    public void deleteByRequestId(String requestId) {
        jdbc.update("DELETE FROM idempotent_request WHERE request_id = ?", requestId);
    }

    /** 查询已登记的幂等请求。 */
    public Optional<IdempotentRecord> find(String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT request_id, request_type, fingerprint, http_status, response_body, created_at" +
                            " FROM idempotent_request WHERE request_id = ?",
                    (rs, rowNum) -> new IdempotentRecord(
                            rs.getString("request_id"),
                            rs.getString("request_type"),
                            rs.getString("fingerprint"),
                            rs.getInt("http_status"),
                            rs.getString("response_body"),
                            rs.getTimestamp("created_at").toInstant()),
                    requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 幂等记录快照。
     */
    public record IdempotentRecord(
            String requestId,
            String requestType,
            String fingerprint,
            int httpStatus,
            String responseBody,
            Instant createdAt
    ) {
    }
}
