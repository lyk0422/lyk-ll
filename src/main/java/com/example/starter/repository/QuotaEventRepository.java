package com.example.starter.repository;

import com.example.starter.domain.QuotaEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 解封额度消费轨迹数据访问。
 */
@Repository
public class QuotaEventRepository {

    private static final RowMapper<QuotaEvent> MAPPER = (rs, n) -> new QuotaEvent(
            rs.getLong("id"), rs.getLong("permit_id"), rs.getLong("task_id"),
            rs.getInt("valve_version"), rs.getInt("quota_used_after"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public QuotaEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记一次额度消费；必须与开度写入、额度扣减在同一事务内调用。
     */
    public void insert(long permitId, long taskId, int valveVersion,
                       int quotaUsedAfter, java.time.Instant createdAt) {
        jdbc.update("INSERT INTO unseal_quota_event"
                        + " (permit_id, task_id, valve_version, quota_used_after, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                permitId, taskId, valveVersion, quotaUsedAfter, Timestamp.from(createdAt));
    }

    /**
     * 按解封单查询额度轨迹，按主键升序。
     */
    public List<QuotaEvent> findByPermitId(long permitId) {
        return jdbc.query("SELECT id, permit_id, task_id, valve_version, quota_used_after, created_at"
                + " FROM unseal_quota_event WHERE permit_id = ? ORDER BY id", MAPPER, permitId);
    }
}
