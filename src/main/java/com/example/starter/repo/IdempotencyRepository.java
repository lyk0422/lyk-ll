package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 幂等请求记录：request_key 唯一，同键异指纹返回 409。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdemRecord> MAPPER = (rs, n) -> new IdemRecord(
            rs.getString("request_key"),
            rs.getString("fingerprint"),
            rs.getString("response_ref"));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdemRecord> find(String requestKey) {
        List<IdemRecord> rows = jdbc.query(
                "SELECT request_key, fingerprint, response_ref FROM idempotent_request WHERE request_key = ?",
                MAPPER, requestKey);
        return rows.stream().findFirst();
    }

    /**
     * 幂等插入；request_key 唯一冲突时返回 false（调用方改走 find 比对指纹）。
     */
    public boolean insert(String requestKey, String fingerprint, String responseRef, Instant now) {
        int affected = jdbc.update(
                "INSERT IGNORE INTO idempotent_request (request_key, fingerprint, response_ref, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                requestKey, fingerprint, responseRef, Timestamp.from(now));
        return affected > 0;
    }

    /**
     * 幂等记录。
     */
    public record IdemRecord(String requestKey, String fingerprint, String responseRef) {
    }
}
