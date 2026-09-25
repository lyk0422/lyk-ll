package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求记录：指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果；同键异参由服务层比对指纹后返回 409。
 */
@Repository
public class IdempotentRepository {

    /**
     * 幂等记录视图。
     */
    public record IdempotentRecord(
            String requestId,
            String requestType,
            String fingerprint,
            int httpStatus,
            String responseBody,
            Long planId,
            LocalDateTime createdAt) {
    }

    private final JdbcTemplate jdbc;

    public IdempotentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public IdempotentRecord find(String requestId) {
        var list = jdbc.query("""
                SELECT request_id, request_type, fingerprint, http_status, response_body, plan_id, created_at
                FROM idempotent_request WHERE request_id = ?
                """, (rs, n) -> new IdempotentRecord(
                rs.getString("request_id"),
                rs.getString("request_type"),
                rs.getString("fingerprint"),
                rs.getInt("http_status"),
                rs.getString("response_body"),
                (Long) rs.getObject("plan_id"),
                rs.getObject("created_at", LocalDateTime.class)), requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 尝试落库首次结果。若并发下键已存在返回 false（调用方重新读取首次结果）。
     */
    public boolean trySave(String requestId, String requestType, String fingerprint, int httpStatus,
                           String responseBody, Long planId) {
        try {
            jdbc.update("""
                    INSERT INTO idempotent_request
                        (request_id, request_type, fingerprint, http_status, response_body, plan_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, requestId, requestType, fingerprint, httpStatus, responseBody, planId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void touchTimestamp(String requestId) {
        jdbc.update("UPDATE idempotent_request SET created_at = ? WHERE request_id = ?",
                Timestamp.valueOf(LocalDateTime.now()), requestId);
    }
}
