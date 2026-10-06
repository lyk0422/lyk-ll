package com.example.starter.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 写操作幂等记录数据访问。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 幂等记录行。
     */
    public record IdemRecord(String requestId, String fingerprint, String endpoint, String responseBody) {
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdemRecord> find(String requestId) {
        return jdbc.sql("SELECT request_id, fingerprint, endpoint, response_body FROM idempotency_record"
                        + " WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(IdemRecord.class)
                .optional();
    }

    /**
     * 写入幂等记录；requestId 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String requestId, String fingerprint, String endpoint, String responseBody,
                       LocalDateTime createdAt) {
        jdbc.sql("INSERT INTO idempotency_record (request_id, fingerprint, endpoint, response_body, created_at)"
                        + " VALUES (:requestId, :fingerprint, :endpoint, :responseBody, :createdAt)")
                .param("requestId", requestId)
                .param("fingerprint", fingerprint)
                .param("endpoint", endpoint)
                .param("responseBody", responseBody)
                .param("createdAt", createdAt)
                .update();
    }
}
