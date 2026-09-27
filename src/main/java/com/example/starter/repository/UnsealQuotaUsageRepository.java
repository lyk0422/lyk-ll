package com.example.starter.repository;

import com.example.starter.domain.UnsealQuotaUsage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 解封额度消费轨迹数据访问。
 */
@Repository
public class UnsealQuotaUsageRepository {

    private static final RowMapper<UnsealQuotaUsage> MAPPER = (rs, n) -> new UnsealQuotaUsage(
            rs.getLong("id"), rs.getLong("unseal_id"), rs.getLong("task_id"),
            rs.getString("request_id"), rs.getTimestamp("used_at").toInstant());

    private final JdbcTemplate jdbc;

    public UnsealQuotaUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记一次额度消费；必须与额度计数更新在同一事务内调用。
     */
    public void insert(long unsealId, long taskId, String requestId, Instant usedAt) {
        jdbc.update("INSERT INTO unseal_quota_usage (unseal_id, task_id, request_id, used_at)"
                        + " VALUES (?, ?, ?, ?)",
                unsealId, taskId, requestId, Timestamp.from(usedAt));
    }

    /**
     * 查询解封单的额度消费轨迹，按主键升序。
     */
    public List<UnsealQuotaUsage> findByUnsealId(long unsealId) {
        return jdbc.query("SELECT id, unseal_id, task_id, request_id, used_at"
                + " FROM unseal_quota_usage WHERE unseal_id = ? ORDER BY id", MAPPER, unsealId);
    }
}
