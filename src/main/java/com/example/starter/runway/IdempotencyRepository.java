package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 幂等记录数据访问：idempotency_record 表。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdemRow> MAPPER = (rs, rowNum) -> new IdemRow(
            rs.getString("scope"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotencyRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 幂等记录行。
     *
     * @param scope        作用域
     * @param requestId    请求标识
     * @param fingerprint  请求指纹
     * @param responseBody 首次响应报文（JSON）
     */
    public record IdemRow(String scope, String requestId, String fingerprint, String responseBody) {
    }

    public Optional<IdemRow> find(String scope, String requestId) {
        List<IdemRow> rows = jdbc.query(
                "SELECT * FROM idempotency_record WHERE scope = ? AND request_id = ?",
                MAPPER, scope, requestId);
        return rows.stream().findFirst();
    }

    public void insert(String scope, String requestId, String fingerprint, String responseBody) {
        jdbc.update("INSERT INTO idempotency_record (scope, request_id, fingerprint, response_body, created_at)"
                        + " VALUES (?,?,?,?,?)",
                scope, requestId, fingerprint, responseBody, Timestamp.from(clock.instant()));
    }
}
