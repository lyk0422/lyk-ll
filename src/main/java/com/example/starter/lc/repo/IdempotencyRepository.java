package com.example.starter.lc.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * requestId 幂等记录数据访问；http_status=0 表示首次处理尚在进行中（占位记录）。
 */
@Repository
public class IdempotencyRepository {

    private static final IdempotentRowMapper ROW_MAPPER = new IdempotentRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 尝试插入“处理中”占位记录（主键即 requestId）。
     *
     * @return true 表示抢占成功，由当前请求处理；false 表示 requestId 已存在
     */
    public boolean tryInsertProcessing(String requestId, String fingerprint, String operation, Instant now) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO idempotent_record
                    (request_id, fingerprint, operation, http_status, result_body, created_at)
                VALUES (?, ?, ?, 0, '', ?)
                """, requestId, fingerprint, operation, Timestamp.from(now));
        return inserted > 0;
    }

    /** 按 requestId 查询，不存在返回 null。 */
    public IdempotentRow find(String requestId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM idempotent_record WHERE request_id = ?", ROW_MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 业务处理完成后写入首次响应（成功或确定性业务失败），保留指纹不变。 */
    public void complete(String requestId, int httpStatus, String resultBody) {
        jdbcTemplate.update(
                "UPDATE idempotent_record SET http_status = ?, result_body = ? WHERE request_id = ?",
                httpStatus, resultBody, requestId);
    }

    /** 非预期异常时删除占位记录，使 requestId 可被客户端重试。 */
    public void delete(String requestId) {
        jdbcTemplate.update("DELETE FROM idempotent_record WHERE request_id = ? AND http_status = 0",
                requestId);
    }

    private static final class IdempotentRowMapper implements RowMapper<IdempotentRow> {

        @Override
        public IdempotentRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new IdempotentRow(
                    rs.getString("request_id"),
                    rs.getString("fingerprint"),
                    rs.getString("operation"),
                    rs.getInt("http_status"),
                    rs.getString("result_body"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
