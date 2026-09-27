package com.example.starter.runway;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 跑道协调数据访问：跑道级排它锁（runway_coordination_lock）与例行巡检唯一占用
 * （runway_inspection_occupancy）。须在事务内调用，锁随事务提交/回滚释放。
 */
@Repository
public class RunwayCoordinationRepository {

    private final JdbcTemplate jdbc;

    public RunwayCoordinationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 取得指定跑道的事务级排它锁：先确保锁行存在，再 SELECT ... FOR UPDATE。
     * 同跑道的例行开始/完成与紧急接报在此串行化；不同跑道使用不同行，互不阻塞。
     */
    public void lockRunway(String runwayId) {
        ensureLockRow(runwayId);
        jdbc.queryForList("SELECT runway_id FROM runway_coordination_lock WHERE runway_id = ? FOR UPDATE",
                String.class, runwayId);
    }

    private void ensureLockRow(String runwayId) {
        try {
            jdbc.update("INSERT INTO runway_coordination_lock (runway_id) VALUES (?)", runwayId);
        } catch (DuplicateKeyException ignored) {
            // 锁行已存在即可
        }
    }

    /**
     * 该跑道当前是否存在进行中紧急关闭事件（active_flag=1）。
     */
    public boolean hasActiveEmergency(String runwayId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_event WHERE runway_id = ? AND active_flag = 1",
                Integer.class, runwayId);
        return count != null && count > 0;
    }

    /**
     * 占用跑道开始例行巡检：主键保证同跑道唯一进行中例行巡检，冲突时抛 DuplicateKeyException。
     */
    public void occupy(String runwayId, long planId, Instant since) {
        jdbc.update("INSERT INTO runway_inspection_occupancy (runway_id, plan_id, occupied_since) VALUES (?,?,?)",
                runwayId, planId, Timestamp.from(since));
    }

    /**
     * 释放跑道例行巡检占用（正常完成或紧急中止）。
     */
    public void release(String runwayId) {
        jdbc.update("DELETE FROM runway_inspection_occupancy WHERE runway_id = ?", runwayId);
    }
}
