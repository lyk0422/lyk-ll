package com.example.starter.blast.repo;

import com.example.starter.blast.domain.BizType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 幂等记录仓储。
 * 主键 (request_id, biz_type) 保证同一业务键在并发下只有一条首次记录。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotentRecord> ROW_MAPPER = (rs, rowNum) -> new IdempotentRecord(
            rs.getString("request_id"),
            BizType.valueOf(rs.getString("biz_type")),
            rs.getString("fingerprint"),
            rs.getInt("http_status"),
            rs.getString("response_json"));

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<IdempotentRecord> find(String requestId, BizType bizType) {
        return jdbcTemplate.query(
                "SELECT request_id, biz_type, fingerprint, http_status, response_json "
                        + "FROM idempotent_request WHERE request_id = ? AND biz_type = ?",
                ROW_MAPPER, requestId, bizType.name()).stream().findFirst();
    }

    /**
     * 插入首次结果；并发撞键时返回 false 由调用方重查，不覆盖已有记录。
     */
    public boolean insert(String requestId, BizType bizType, String fingerprint,
                          int httpStatus, String responseJson, Instant now) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO idempotent_request "
                            + "(request_id, biz_type, fingerprint, http_status, response_json, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    requestId, bizType.name(), fingerprint, httpStatus, responseJson,
                    java.sql.Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /**
     * 幂等记录。
     */
    public record IdempotentRecord(String requestId, BizType bizType, String fingerprint,
                                   int httpStatus, String responseJson) {
    }
}
