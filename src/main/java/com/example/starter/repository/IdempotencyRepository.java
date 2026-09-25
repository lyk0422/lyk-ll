package com.example.starter.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 幂等记录数据访问（idempotency_record）。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 幂等记录行。
     *
     * @param requestId    幂等请求ID
     * @param fingerprint  请求指纹
     * @param httpStatus   首次响应HTTP状态码
     * @param responseBody 首次响应报文
     */
    public record IdempotencyRow(String requestId, String fingerprint, int httpStatus, String responseBody) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求ID查询幂等记录。
     */
    public Optional<IdempotencyRow> find(String requestId) {
        return jdbc.query("SELECT request_id, fingerprint, http_status, response_body FROM idempotency_record WHERE request_id = ?",
                (rs, rowNum) -> new IdempotencyRow(
                        rs.getString("request_id"),
                        rs.getString("fingerprint"),
                        rs.getInt("http_status"),
                        rs.getString("response_body")),
                requestId).stream().findFirst();
    }

    /**
     * 写入幂等记录；同 requestId 并发写入时仅一条成功。
     *
     * @return true 写入成功；false 已存在同键记录
     */
    public boolean tryInsert(String requestId, String fingerprint, int httpStatus, String responseBody, Instant now) {
        try {
            jdbc.update("INSERT INTO idempotency_record (request_id, fingerprint, http_status, response_body, created_at) VALUES (?,?,?,?,?)",
                    requestId, fingerprint, httpStatus, responseBody, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
