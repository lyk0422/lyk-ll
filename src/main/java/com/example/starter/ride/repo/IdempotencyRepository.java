package com.example.starter.ride.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求仓储：requestId 主键，指纹冲突由调用方判定 409。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record StoredRequest(String requestType, String rideId, String fingerprint,
                                int responseCode, String responseBody) {
    }

    public StoredRequest find(String requestId) {
        List<StoredRequest> rows = jdbcTemplate.query(
                "SELECT request_type, ride_id, fingerprint, response_code, response_body "
                        + "FROM idempotent_request WHERE request_id = ?",
                (rs, n) -> new StoredRequest(rs.getString("request_type"), rs.getString("ride_id"),
                        rs.getString("fingerprint"), rs.getInt("response_code"),
                        rs.getString("response_body")),
                requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 插入首次请求记录。
     *
     * @return true 插入成功（获得首响权）；false 表示并发下已被同 requestId 抢占
     */
    public boolean insert(String requestId, String requestType, String rideId,
                          String fingerprint, int responseCode, String responseBody, Instant createdAt) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO idempotent_request (request_id, request_type, ride_id, fingerprint, "
                            + "response_code, response_body, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    requestId, requestType, rideId, fingerprint, responseCode, responseBody,
                    Timestamp.from(createdAt));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }
}
