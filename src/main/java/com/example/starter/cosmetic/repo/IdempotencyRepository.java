package com.example.starter.cosmetic.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.example.starter.cosmetic.domain.IdempotencyRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 幂等处理记录的数据访问；request_id 为主键，依靠唯一约束拦截并发重放。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查询处理记录。
     */
    public Optional<IdempotencyRow> find(String requestId) {
        return jdbc.sql("SELECT * FROM idempotency_record WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query((rs, rowNum) -> {
                    int status = rs.getInt("response_status");
                    return new IdempotencyRow(
                            rs.getString("request_id"),
                            rs.getString("endpoint"),
                            rs.getString("fingerprint"),
                            rs.wasNull() ? null : status,
                            rs.getString("response_body"),
                            rs.getTimestamp("created_at").toInstant());
                })
                .optional();
    }

    /**
     * 在处理事务起始插入占位记录（响应待回填）；同键并发插入将由主键约束串行化。
     */
    public void insertMarker(String requestId, String endpoint, String fingerprint, Instant now) {
        jdbc.sql("INSERT INTO idempotency_record (request_id, endpoint, fingerprint, created_at) VALUES (:requestId, :endpoint, :fingerprint, :now)")
                .param("requestId", requestId)
                .param("endpoint", endpoint)
                .param("fingerprint", fingerprint)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * 回填首次处理结果。
     */
    public void updateResponse(String requestId, int responseStatus, String responseBody) {
        jdbc.sql("UPDATE idempotency_record SET response_status = :status, response_body = :body WHERE request_id = :requestId")
                .param("status", responseStatus)
                .param("body", responseBody)
                .param("requestId", requestId)
                .update();
    }
}
