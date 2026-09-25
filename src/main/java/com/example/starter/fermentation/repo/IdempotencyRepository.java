package com.example.starter.fermentation.repo;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求仓储：指纹覆盖 requestId 与全部业务字段。
 * 同键同参回放首次结果；同键异参由服务层判定后返回 409 且不改变任何业务数据。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 幂等记录行。
     */
    public record IdempotentRow(
            String requestId,
            String requestType,
            String fingerprint,
            int statusCode,
            String responseJson,
            Long batchId,
            Instant createdAt) {
    }

    private static final RowMapper<IdempotentRow> ROW_MAPPER = (rs, n) -> new IdempotentRow(
            rs.getString("request_id"),
            rs.getString("request_type"),
            rs.getString("fingerprint"),
            rs.getInt("status_code"),
            rs.getString("response_json"),
            (Long) rs.getObject("batch_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 requestId 查询已受理记录。
     */
    public Optional<IdempotentRow> find(String requestId) {
        return jdbc.sql("SELECT * FROM idempotent_request WHERE request_id = ?")
                .param(requestId)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * 落库首次受理结果。requestId 主键冲突时由调用方重新查询比对指纹。
     */
    public void insert(String requestId,
                       String requestType,
                       String fingerprint,
                       int statusCode,
                       String responseJson,
                       Long batchId) {
        jdbc.sql("""
                        INSERT INTO idempotent_request
                            (request_id, request_type, fingerprint, status_code, response_json, batch_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)
                .param(requestId)
                .param(requestType)
                .param(fingerprint)
                .param(statusCode)
                .param(responseJson)
                .param(batchId)
                .update();
    }
}
