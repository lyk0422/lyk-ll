package com.example.starter.waterlogging.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 流域分区启停序列锁数据访问：同一分区同时刻只允许一套活跃启停序列。
 */
@Repository
public class ZoneSequenceLockRepository {

    public ZoneSequenceLockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    protected final JdbcTemplate jdbcTemplate;

    /**
     * 尝试占用分区序列；唯一约束保证并发仅一套成功。
     *
     * @return true 占用成功；false 分区已有活跃序列
     */
    public boolean tryAcquire(String basinZone, long planId, LocalDateTime lockedAt) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO zone_sequence_lock (basin_zone, plan_id, locked_at) VALUES (?, ?, ?)",
                    basinZone, planId, Timestamp.valueOf(lockedAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 查询分区当前活跃序列所属预降单。 */
    public Optional<Long> findActivePlanId(String basinZone) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT plan_id FROM zone_sequence_lock WHERE basin_zone = ?",
                (rs, n) -> rs.getLong("plan_id"), basinZone);
        return ids.stream().findFirst();
    }

    /** 序列完成后释放分区锁。 */
    public int releaseByPlan(long planId) {
        return jdbcTemplate.update("DELETE FROM zone_sequence_lock WHERE plan_id = ?", planId);
    }
}
