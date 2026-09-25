package com.example.starter.cutover.repository;

import com.example.starter.cutover.po.IdempotencyRecordPO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 幂等记录数据访问；(request_id, scope) 唯一约束保证并发重放/同键异参检测。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecordPO> MAPPER = (rs, rowNum) -> {
        IdempotencyRecordPO po = new IdempotencyRecordPO();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setScope(rs.getString("scope"));
        po.setFingerprint(rs.getString("fingerprint"));
        po.setResourceId(rs.getString("resource_id"));
        po.setResponseJson(rs.getString("response_json"));
        po.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<IdempotencyRecordPO> find(String requestId, String scope) {
        List<IdempotencyRecordPO> list = jdbcTemplate.query(
                "SELECT * FROM idempotency_record WHERE request_id = ? AND scope = ?",
                MAPPER, requestId, scope);
        return list.stream().findFirst();
    }

    /**
     * 插入幂等记录；唯一约束冲突返回 false（并发重放已被抢先登记）。
     */
    public boolean tryInsert(String requestId, String scope, String fingerprint,
                             String resourceId, String responseJson, LocalDateTime createdAt) {
        int rows = jdbcTemplate.update(
                "INSERT INTO idempotency_record "
                        + "(request_id, scope, fingerprint, resource_id, response_json, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                requestId, scope, fingerprint, resourceId, responseJson,
                Timestamp.valueOf(createdAt));
        return rows == 1;
    }

    /**
     * 回填首次处理产生的资源标识与响应快照。
     */
    public void updateOutcome(long id, String resourceId, String responseJson) {
        jdbcTemplate.update(
                "UPDATE idempotency_record SET resource_id = ?, response_json = ? WHERE id = ?",
                resourceId, responseJson, id);
    }
}
