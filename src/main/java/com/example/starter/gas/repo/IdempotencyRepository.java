package com.example.starter.gas.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 通用幂等记录访问：指纹覆盖 requestId 与全部业务字段。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取首次响应快照；不存在返回 null。 */
    public String findResponse(String requestId) {
        List<String> values = jdbc.query(
                "SELECT response_json FROM idempotent_request WHERE request_id = ?",
                (rs, rowNum) -> rs.getString("response_json"), requestId);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 读取首次指纹；不存在返回 null。 */
    public String findFingerprint(String requestId) {
        List<String> values = jdbc.query(
                "SELECT fingerprint FROM idempotent_request WHERE request_id = ?",
                (rs, rowNum) -> rs.getString("fingerprint"), requestId);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * 写入幂等记录；同 requestId 并发写入抛 {@link DuplicateKeyException}。
     */
    public void insert(String requestId, String requestType, String fingerprint, String responseJson) {
        jdbc.update(
                "INSERT INTO idempotent_request (request_id, request_type, fingerprint, response_json) "
                        + "VALUES (?, ?, ?, ?)",
                requestId, requestType, fingerprint, responseJson);
    }
}
