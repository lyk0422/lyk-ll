package com.example.starter.flood.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等记录仓储：request_id 为主键，指纹覆盖 requestId 与全部业务字段。
 */
@Repository
public class IdempotentRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 幂等登记结果。
     *
     * @param created true=本次首次插入；false=该 requestId 已存在
     * @param fingerprintMismatch 已存在但指纹不同（同键异参）
     * @param refId 首次登记时关联的业务主键
     */
    public record IdempotencyOutcome(boolean created, boolean fingerprintMismatch, Long refId) {
    }

    /**
     * 尝试登记幂等键。唯一键冲突时回查并比对指纹；
     * 若并发下首事务尚未提交导致回查不到，返回进行中标记由调用方返回 409 稍后重试。
     */
    public IdempotencyOutcome register(String requestId, String bizType, String fingerprint,
                                       LocalDateTime now) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO idempotent_request(request_id, biz_type, fingerprint, "
                            + "ref_id, created_at) VALUES (?,?,?,?,?)",
                    requestId, bizType, fingerprint, null, Timestamp.valueOf(now));
            return new IdempotencyOutcome(true, false, null);
        } catch (DuplicateKeyException e) {
            Map<String, Object> row = findByRequestId(requestId);
            if (row == null) {
                // 同键首请求事务尚未提交：无法判定指纹，告知客户端稍后重试
                return new IdempotencyOutcome(false, true, null);
            }
            String existing = (String) row.get("fingerprint");
            Long refId = (Long) row.get("ref_id");
            return new IdempotencyOutcome(false, !fingerprint.equals(existing), refId);
        }
    }

    public Map<String, Object> findByRequestId(String requestId) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT request_id, biz_type, fingerprint, ref_id, created_at "
                        + "FROM idempotent_request WHERE request_id = ?", requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 首次业务写入成功后回填关联主键。
     */
    public void bindRef(String requestId, Long refId) {
        jdbcTemplate.update("UPDATE idempotent_request SET ref_id = ? WHERE request_id = ?",
                refId, requestId);
    }
}
