package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等键登记数据访问。request_id 为主键，并发下同键仅一个事务可登记成功。
 */
@Repository
public class IdempotencyRepository {

    /** 已登记的幂等记录。 */
    public record IdempotencyRecord(String requestId, String endpoint, String fingerprint,
                                    int httpStatus, String responseBody, OffsetDateTime createdAt) {
    }

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, n) -> map(rs);

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static IdempotencyRecord map(ResultSet rs) throws SQLException {
        return new IdempotencyRecord(rs.getString("request_id"), rs.getString("endpoint"),
                rs.getString("fingerprint"), rs.getInt("http_status"), rs.getString("response_body"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    public Optional<IdempotencyRecord> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_key WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public void insert(String requestId, String endpoint, String fingerprint, int httpStatus,
                       String responseBody, OffsetDateTime now) {
        jdbc.update("INSERT INTO idempotency_key (request_id, endpoint, fingerprint, http_status,"
                        + " response_body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                requestId, endpoint, fingerprint, httpStatus, responseBody, now);
    }
}
