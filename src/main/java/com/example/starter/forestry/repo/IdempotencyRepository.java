package com.example.starter.forestry.repo;

import java.sql.Date;
import java.time.LocalDate;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.forestry.domain.IdempotentOperation;
import com.example.starter.forestry.po.IdempotentRecordPO;

/**
 * 幂等请求记录仓储。记录以 (operation, requestId) 为唯一键：
 * PENDING 表示首次请求处理中（并发同键等待），DONE 保存首次结果快照（含业务拒绝）。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试占位为 PENDING。
     *
     * @return true 占位成功（当前请求为首处理者）；false 已存在同键记录
     */
    public boolean tryInsertPending(IdempotentOperation operation, String requestId,
                                    String fingerprint, LocalDate today) {
        try {
            jdbc.update("""
                    INSERT INTO idempotent_request
                        (operation, request_id, fingerprint, status, response_type, response_status,
                         ref_id, response_json, created_date)
                    VALUES (?, ?, ?, 'PENDING', NULL, NULL, NULL, NULL, ?)
                    """,
                    operation.name(), requestId, fingerprint, Date.valueOf(today));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 查询同键记录，不存在返回 null。
     */
    public IdempotentRecordPO find(IdempotentOperation operation, String requestId) {
        var rows = jdbc.query("""
                        SELECT operation, request_id, fingerprint, status, response_type, response_status,
                               ref_id, response_json, created_date
                        FROM idempotent_request
                        WHERE operation = ? AND request_id = ?
                        """,
                (rs, n) -> new IdempotentRecordPO(
                        rs.getString("operation"),
                        rs.getString("request_id"),
                        rs.getString("fingerprint"),
                        rs.getString("status"),
                        rs.getString("response_type"),
                        rs.getObject("response_status", Integer.class),
                        rs.getString("ref_id"),
                        rs.getString("response_json"),
                        rs.getDate("created_date") == null ? null : rs.getDate("created_date").toLocalDate()),
                operation.name(), requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 首次处理完成：写入结果快照并置 DONE。
     *
     * @param responseStatus 业务失败时为其 HTTP 状态码；成功时为 null
     */
    public void markDone(IdempotentOperation operation, String requestId,
                         String responseType, Integer responseStatus, String refId, String responseJson) {
        jdbc.update("""
                        UPDATE idempotent_request
                           SET status = 'DONE', response_type = ?, response_status = ?,
                               ref_id = ?, response_json = ?
                         WHERE operation = ? AND request_id = ? AND status = 'PENDING'
                        """,
                responseType, responseStatus, refId, responseJson, operation.name(), requestId);
    }
}
