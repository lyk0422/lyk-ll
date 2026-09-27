package com.example.starter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 测试用表清理器：清空业务表并重置阀门状态，保留合成门站配置。
 */
@Component
public class TestTableCleaner {

    private final JdbcTemplate jdbc;

    public TestTableCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clean() {
        jdbc.update("DELETE FROM odorant_reading");
        jdbc.update("DELETE FROM pressure_reading");
        jdbc.update("DELETE FROM adjustment_issue");
        jdbc.update("DELETE FROM linkage_task");
        jdbc.update("DELETE FROM lock_order");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM daily_close");
        jdbc.update("DELETE FROM idempotent_record");
        jdbc.update("DELETE FROM tank_ledger");
        jdbc.update("DELETE FROM refill_order");
        jdbc.update("DELETE FROM refill_event");
        jdbc.update("DELETE FROM injection_plan");
        jdbc.update("DELETE FROM odorant_tank");
        jdbc.update("DELETE FROM valve_state");
        jdbc.update("""
                INSERT INTO valve_state
                    (station_id, injection_locked, alarm_active, breach_count,
                     current_injection_rate_mg_m3, current_pressure_mpa, updated_at)
                SELECT id, FALSE, FALSE, 0, 20.000, NULL, CURRENT_TIMESTAMP FROM station
                """);
    }
}
