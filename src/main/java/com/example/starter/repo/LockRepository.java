package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.Interval;
import com.example.starter.domain.ResourceType;

/**
 * 资源锁定（引航员/拖轮）与执行中唯一锁的数据访问。
 */
@Repository
public class LockRepository {

    private final JdbcTemplate jdbc;

    public LockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 对资源主表行加写锁（SELECT ... FOR UPDATE），用于确认/改期事务内串行化。 */
    public void lockResourceRows(ResourceType type, List<Long> resourceIds) {
        String table = type == ResourceType.PILOT ? "pilot" : "tugboat";
        for (Long id : resourceIds.stream().sorted().toList()) {
            Integer found = jdbc.queryForObject(
                    "SELECT 1 FROM " + table + " WHERE id = ? FOR UPDATE", Integer.class, id);
            if (found == null) {
                throw new IllegalStateException("资源不存在: " + type + " " + id);
            }
        }
    }

    /** 查询资源在窗口内的生效中锁定（半开区间重叠：start < 候选end 且 候选start < end）。 */
    public List<Long> findOverlappingActiveLocks(ResourceType type, long resourceId,
            LocalDateTime start, LocalDateTime end, Long excludePlanId) {
        String sql = "SELECT plan_id FROM resource_lock WHERE resource_type = ? AND resource_id = ? "
                + "AND status = 'ACTIVE' AND window_start < ? AND window_end > ?";
        if (excludePlanId != null) {
            sql += " AND plan_id <> " + excludePlanId;
        }
        return jdbc.queryForList(sql, Long.class, type.name(), resourceId,
                Timestamp.valueOf(end), Timestamp.valueOf(start));
    }

    /** 查询资源全部生效中锁定窗口（用于工时校验），可按计划排除。 */
    public List<Interval> findActiveWindows(ResourceType type, long resourceId, Long excludePlanId) {
        String sql = "SELECT window_start, window_end FROM resource_lock WHERE resource_type = ? "
                + "AND resource_id = ? AND status = 'ACTIVE'";
        if (excludePlanId != null) {
            sql += " AND plan_id <> " + excludePlanId;
        }
        return jdbc.query(sql, (rs, rowNum) -> new Interval(
                        rs.getTimestamp("window_start").toLocalDateTime(),
                        rs.getTimestamp("window_end").toLocalDateTime()),
                type.name(), resourceId);
    }

    public void insertLock(ResourceType type, long resourceId, long planId,
            LocalDateTime start, LocalDateTime end) {
        jdbc.update(
                "INSERT INTO resource_lock (resource_type, resource_id, plan_id, window_start, window_end, status) "
                        + "VALUES (?,?,?,?,?,'ACTIVE')",
                type.name(), resourceId, planId, Timestamp.valueOf(start), Timestamp.valueOf(end));
    }

    /** 释放计划全部生效中锁定，返回影响行数。 */
    public int releaseByPlan(long planId) {
        return jdbc.update("UPDATE resource_lock SET status = 'RELEASED' WHERE plan_id = ? AND status = 'ACTIVE'",
                planId);
    }

    public int countActiveLocksByPlan(long planId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lock WHERE plan_id = ? AND status = 'ACTIVE'", Integer.class, planId);
        return count == null ? 0 : count;
    }

    /** 登记执行中计划；同一引航员主键冲突即违反“同时刻仅一条执行中计划”。 */
    public void insertExecutingLock(long pilotId, long planId) {
        jdbc.update("INSERT INTO executing_lock (pilot_id, plan_id) VALUES (?,?)", pilotId, planId);
    }

    public void deleteExecutingLock(long pilotId) {
        jdbc.update("DELETE FROM executing_lock WHERE pilot_id = ?", pilotId);
    }

    public int countExecutingLocks() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM executing_lock", Integer.class);
        return count == null ? 0 : count;
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM resource_lock");
        jdbc.update("DELETE FROM executing_lock");
    }
}
