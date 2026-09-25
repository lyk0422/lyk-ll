package com.example.starter.dao;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 操作幂等台账：requestId 为全局唯一键，指纹覆盖 requestId 与全部业务字段。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRow> MAPPER = (rs, n) -> new IdempotencyRow(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("fingerprint"),
            rs.getInt("status_code"),
            rs.getString("response_json"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取首次受理记录（同键重放）。 */
    public Optional<IdempotencyRow> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 登记首次受理；并发下唯一索引保证只有一个请求成功，
     * 其余抛 {@link DuplicateKeyException} 后按重放/409 处理。
     */
    public void insert(IdempotencyRow row) {
        jdbc.update("INSERT INTO idempotency_record "
                        + "(request_id, operation, fingerprint, status_code, response_json, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                row.requestId(), row.operation(), row.fingerprint(), row.statusCode(),
                row.responseJson(), java.sql.Timestamp.from(row.createdAtUtc() == null
                        ? Instant.now() : row.createdAtUtc()));
    }
}
