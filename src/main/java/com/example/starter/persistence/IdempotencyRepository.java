package com.example.starter.persistence;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 通用幂等记录数据访问。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IdempotencyPO> ROW_MAPPER = (rs, n) -> new IdempotencyPO(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("fingerprint"),
            rs.getInt("http_status"),
            rs.getString("result_body"),
            rs.getTimestamp("created_at").toInstant());

    /** 插入幂等记录；重复键由调用方捕获后走同参重放/异参 409 判定。 */
    public void insert(IdempotencyPO po) {
        jdbc.update("""
                INSERT INTO idempotency_record
                    (request_id, operation, fingerprint, http_status, result_body, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                po.requestId(), po.operation(), po.fingerprint(), po.httpStatus(),
                po.resultBody(), java.sql.Timestamp.from(po.createdAt()));
    }

    /** 按 requestId 查询。 */
    public Optional<IdempotencyPO> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?",
                ROW_MAPPER, requestId).stream().findFirst();
    }
}
