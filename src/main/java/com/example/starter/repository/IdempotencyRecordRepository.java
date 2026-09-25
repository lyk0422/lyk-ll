package com.example.starter.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.model.IdempotencyRecord;

/**
 * 幂等记录数据访问；request_id 主键兜底并发同键写入。
 */
@Repository
public class IdempotencyRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入首次成功响应快照；并发同键时由主键约束抛出重复键异常。
     */
    public void insert(String requestId, String apiName, String fingerprint, String responseBody,
                       Instant now) {
        jdbcTemplate.update(
                "INSERT INTO idempotency_record (request_id, api_name, fingerprint, response_body, created_at)"
                        + " VALUES (?,?,?,?,?)",
                requestId, apiName, fingerprint, responseBody, StationRepository.toOffsetDateTime(now));
    }

    /**
     * 按幂等键查询记录。
     */
    public Optional<IdempotencyRecord> findByRequestId(String requestId) {
        return jdbcTemplate.query("SELECT * FROM idempotency_record WHERE request_id = ?",
                this::map, requestId).stream().findFirst();
    }

    private IdempotencyRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new IdempotencyRecord(rs.getString("request_id"), rs.getString("api_name"),
                rs.getString("fingerprint"), rs.getString("response_body"),
                StationRepository.toInstant(rs, "created_at"));
    }
}
