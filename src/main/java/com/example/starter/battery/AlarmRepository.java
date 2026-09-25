package com.example.starter.battery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 告警数据访问。
 */
@Repository
public class AlarmRepository {

    private final JdbcTemplate jdbc;

    public AlarmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 记录告警：独立事务提交，调用方业务回滚时告警仍然保留。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(String batteryCode, String reason, long now) {
        jdbc.update("INSERT INTO alarm (battery_code, reason, created_at) VALUES (?,?,?)",
                batteryCode, reason, now);
    }

    /** 统计某电池告警数（测试与排查用）。 */
    public int countByBattery(String batteryCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alarm WHERE battery_code = ?", Integer.class, batteryCode);
        return count == null ? 0 : count;
    }
}
