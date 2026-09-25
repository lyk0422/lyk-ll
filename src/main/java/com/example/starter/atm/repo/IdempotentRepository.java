package com.example.starter.atm.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 幂等请求台账：指纹覆盖 endpoint + 全部业务字段。
 * 同键同参返回首次结果；同键异参判定冲突（409）。
 */
@Repository
public class IdempotentRepository {

    /**
     * @param httpStatus   首次响应状态码
     * @param responseBody 首次响应体 JSON（业务异常也登记，保证失败重放语义一致）
     */
    public record StoredRequest(
            String requestId,
            String endpoint,
            String fingerprint,
            int httpStatus,
            String responseBody,
            long createdAt
    ) {
    }

    private static final RowMapper<StoredRequest> MAPPER = (rs, n) -> new StoredRequest(
            rs.getString("request_id"),
            rs.getString("endpoint"),
            rs.getString("fingerprint"),
            rs.getInt("http_status"),
            rs.getString("response_body"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public IdempotentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入首次请求；request_id 冲突返回 false（已存在同键请求）。 */
    public boolean tryInsert(String requestId, String endpoint, String fingerprint,
                             int httpStatus, String responseBody, long createdAt) {
        int rows = jdbc.update(
                "INSERT INTO idempotent_request (request_id, endpoint, fingerprint, http_status, "
                        + "response_body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                requestId, endpoint, fingerprint, httpStatus, responseBody, createdAt);
        return rows == 1;
    }

    public StoredRequest find(String requestId) {
        List<StoredRequest> list = jdbc.query(
                "SELECT * FROM idempotent_request WHERE request_id = ?", MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 首次执行完成后回填真实响应（异常也回填），供后续重放原样返回。 */
    public void updateResult(String requestId, int httpStatus, String responseBody) {
        jdbc.update(
                "UPDATE idempotent_request SET http_status = ?, response_body = ? WHERE request_id = ?",
                httpStatus, responseBody, requestId);
    }
}
