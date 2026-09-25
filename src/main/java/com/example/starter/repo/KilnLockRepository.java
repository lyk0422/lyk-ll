package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 窑执行占用与窑级行锁。kiln_active_plan 唯一约束保证同窑同时刻只有一个执行中方案；
 * kiln_lock 用于申报阶段对同窑并发事务串行化，保证“并发申报唯一受理”。
 */
@Repository
public class KilnLockRepository {

    private final JdbcTemplate jdbc;

    public KilnLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 对窑加行锁：先幂等插入锁行，再 SELECT ... FOR UPDATE。
     * 同窑并发申报将在此排队，先提交者完成占用校验，后来者可见其结果。
     */
    public void lockKiln(String kilnId) {
        jdbc.update("MERGE INTO kiln_lock (kiln_id) KEY(kiln_id) VALUES (?)", kilnId);
        jdbc.queryForObject("SELECT kiln_id FROM kiln_lock WHERE kiln_id = ? FOR UPDATE", String.class, kilnId);
    }

    /**
     * 尝试占用窑执行槽。命中唯一约束返回 false（已被占用）。
     */
    public boolean tryAcquire(String kilnId, long planId, LocalDateTime acquiredAt) {
        try {
            jdbc.update("INSERT INTO kiln_active_plan (kiln_id, plan_id, acquired_at) VALUES (?, ?, ?)",
                    kilnId, planId, Timestamp.valueOf(acquiredAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void release(String kilnId) {
        jdbc.update("DELETE FROM kiln_active_plan WHERE kiln_id = ?", kilnId);
    }

    /**
     * 切换场景：同一事务内把窑占用直接从旧方案改挂新方案（先停旧后启新原子完成）。
     */
    public int replace(String kilnId, long newPlanId, LocalDateTime acquiredAt) {
        return jdbc.update(
                "UPDATE kiln_active_plan SET plan_id = ?, acquired_at = ? WHERE kiln_id = ?",
                newPlanId, Timestamp.valueOf(acquiredAt), kilnId);
    }

    public Long activePlanId(String kilnId) {
        var ids = jdbc.queryForList(
                "SELECT plan_id FROM kiln_active_plan WHERE kiln_id = ?", Long.class, kilnId);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
