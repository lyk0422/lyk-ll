package com.example.starter.schedule.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 同站同日编排串行化锁：锁表行级锁在事务提交/回滚时自动释放。
 */
@Repository
public class StationDayLockRepository {

    private final JdbcTemplate jdbc;

    public StationDayLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 在当前事务内锁定同站同日的锁行（阻塞直至前一个事务释放）。
     */
    public void lock(long stationId, String dayKey) {
        try {
            jdbc.update("INSERT INTO station_day_lock (station_id, day_key) VALUES (?, ?)",
                    stationId, dayKey);
        } catch (DuplicateKeyException ignored) {
            // 锁行已存在，直接进入行锁等待
        }
        Integer locked = jdbc.query(
                "SELECT station_id FROM station_day_lock WHERE station_id = ? AND day_key = ? FOR UPDATE",
                rs -> rs.next() ? 1 : 0, stationId, dayKey);
        if (locked == null || locked != 1) {
            throw new IllegalStateException("同站同日锁行缺失: stationId=" + stationId);
        }
    }
}
