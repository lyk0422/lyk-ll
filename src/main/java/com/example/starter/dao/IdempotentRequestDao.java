package com.example.starter.dao;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求指纹：(request_id, api_type) 唯一，指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果；同键异参报 409 且不改状态。
 */
@Repository
public class IdempotentRequestDao {

    private final JdbcTemplate jdbc;

    public IdempotentRequestDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdemRow> find(String requestId, String apiType) {
        List<IdemRow> list = jdbc.query(
                "SELECT id, request_id, api_type, fingerprint, ref_key, response_doc, status_code, create_time"
                        + " FROM idempotent_request WHERE request_id = ? AND api_type = ?",
                (rs, n) -> new IdemRow(rs.getLong("id"),
                        rs.getString("request_id"),
                        rs.getString("api_type"),
                        rs.getString("fingerprint"),
                        rs.getString("ref_key"),
                        rs.getString("response_doc"),
                        rs.getInt("status_code"),
                        rs.getTimestamp("create_time").toInstant()),
                requestId, apiType);
        return list.stream().findFirst();
    }

    public void insert(String requestId, String apiType, String fingerprint,
                       String refKey, String responseDoc, int statusCode) {
        jdbc.update(
                "INSERT INTO idempotent_request (request_id, api_type, fingerprint, ref_key, response_doc,"
                        + " status_code, create_time) VALUES (?, ?, ?, ?, ?, ?, ?)",
                requestId, apiType, fingerprint, refKey, responseDoc,
                statusCode, Timestamp.from(Instant.now()));
    }

    /**
     * 业务执行成功后回填首次结果快照与引用键。
     */
    public void updateResult(String requestId, String apiType, String refKey,
                             String responseDoc, int statusCode) {
        jdbc.update(
                "UPDATE idempotent_request SET ref_key = ?, response_doc = ?, status_code = ?"
                        + " WHERE request_id = ? AND api_type = ?",
                refKey, responseDoc, statusCode, requestId, apiType);
    }

    /**
     * 幂等行。
     */
    public record IdemRow(long id, String requestId, String apiType, String fingerprint,
                          String refKey, String responseDoc, int statusCode, Instant createTime) {
    }
}
