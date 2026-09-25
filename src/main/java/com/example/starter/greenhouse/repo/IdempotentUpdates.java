package com.example.starter.greenhouse.repo;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 幂等记录“先占位、后回填”的更新入口：业务事务成功后回填 HTTP 状态与响应体。
 */
@Component
public class IdempotentUpdates {

    private final JdbcTemplate jdbc;

    public IdempotentUpdates(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 业务成功后回填 HTTP 状态与响应体 JSON。 */
    public void fillResponse(String requestId, int httpStatus, String responseBody) {
        jdbc.update("UPDATE idempotent_request SET http_status = ?, response_body = ? WHERE request_id = ?",
                httpStatus, responseBody, requestId);
    }

    static Timestamp timestamp(Instant at) {
        return Timestamp.from(at);
    }
}
