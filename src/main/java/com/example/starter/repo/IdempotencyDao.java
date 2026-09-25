package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 幂等受理记录的数据访问。requestId 全局唯一，指纹覆盖 requestId 与全部业务字段。
 */
@Repository
public class IdempotencyDao {

    /** 幂等受理记录。 */
    public record IdemRow(String requestId, String endpoint, String fingerprint,
                          int responseStatus, String responseBody) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 requestId 查询首次受理记录。 */
    public Optional<IdemRow> find(String requestId) {
        List<IdemRow> rows = jdbc.query(
                "SELECT request_id, endpoint, fingerprint, response_status, response_body"
                        + " FROM idempotency_record WHERE request_id = ?",
                (rs, n) -> new IdemRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getString(5)),
                requestId);
        return rows.stream().findFirst();
    }

    /** 记录首次受理结果。 */
    public void insert(String requestId, String endpoint, String fingerprint,
                       int responseStatus, String responseBody) {
        jdbc.update("INSERT INTO idempotency_record (request_id, endpoint, fingerprint, response_status,"
                        + " response_body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                requestId, endpoint, fingerprint, responseStatus, responseBody,
                Timestamp.from(Instant.now()));
    }
}
