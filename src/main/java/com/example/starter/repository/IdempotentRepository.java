package com.example.starter.repository;

import com.example.starter.domain.IdempotentRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 幂等请求仓储：requestId 主键记录指纹与首次响应。
 *
 * <p>并发同键请求通过“先插入占位行”串行化：唯一索引让后来者阻塞，
 * 先到事务提交后后来者撞主键走重放/409，先到事务回滚则后来者占位成功自行执行。</p>
 */
@Repository
public class IdempotentRepository {

    private static final RowMapper<IdempotentRecord> MAPPER = (rs, rowNum) -> {
        IdempotentRecord r = new IdempotentRecord();
        r.setRequestId(rs.getString("request_id"));
        r.setRequestType(rs.getString("request_type"));
        r.setZoneCode(rs.getString("zone_code"));
        r.setFingerprint(rs.getString("fingerprint"));
        r.setHttpStatus(rs.getInt("http_status"));
        r.setResponseJson(rs.getString("response_json"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        r.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
        return r;
    };

    private final JdbcTemplate jdbcTemplate;

    public IdempotentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按 requestId 查询幂等记录；不存在返回 null。
     */
    public IdempotentRecord find(String requestId) {
        List<IdempotentRecord> list = jdbcTemplate.query(
                "SELECT request_id, request_type, zone_code, fingerprint, http_status, response_json, created_at "
                        + "FROM idempotent_request WHERE request_id = ?", MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 插入占位记录（响应暂为 NULL）。并发同键冲突抛 {@link DuplicateKeyException}。
     */
    public void insertPlaceholder(String requestId, String requestType, String zoneCode, String fingerprint) {
        jdbcTemplate.update(
                "INSERT INTO idempotent_request (request_id, request_type, zone_code, fingerprint, http_status, response_json) "
                        + "VALUES (?, ?, ?, ?, 0, NULL)",
                requestId, requestType, zoneCode, fingerprint);
    }

    /**
     * 同一事务内回填首次成功响应。
     */
    public void saveResponse(String requestId, int httpStatus, String responseJson) {
        jdbcTemplate.update(
                "UPDATE idempotent_request SET http_status = ?, response_json = ? WHERE request_id = ?",
                httpStatus, responseJson, requestId);
    }

    /**
     * 仅用于测试：清理全部幂等记录。
     */
    public int deleteAllForTest() {
        return jdbcTemplate.update("DELETE FROM idempotent_request");
    }
}
