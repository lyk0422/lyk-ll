package com.example.starter.tailings.idempotency;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等记录存取：requestId 主键，指纹覆盖 requestId 与全部业务字段。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等记录行。
     *
     * @param requestId    幂等请求ID
     * @param fingerprint  requestId+全部业务字段的SHA-256指纹
     * @param responseBody 首次响应JSON快照
     */
    public record IdempotencyRow(String requestId, String fingerprint, String responseBody) {
    }

    public Optional<IdempotencyRow> find(String requestId) {
        return jdbc.query(
                        "SELECT request_id, fingerprint, response_body FROM idempotency_record WHERE request_id = ?",
                        (rs, rowNum) -> new IdempotencyRow(
                                rs.getString("request_id"),
                                rs.getString("fingerprint"),
                                rs.getString("response_body")),
                        requestId)
                .stream()
                .findFirst();
    }

    public void insert(String requestId, String fingerprint, String responseBody) {
        jdbc.update(
                "INSERT INTO idempotency_record (request_id, fingerprint, response_body) VALUES (?, ?, ?)",
                requestId, fingerprint, responseBody);
    }
}
