package com.example.starter.greenhouse.testsupport;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试间数据清理：保持表结构，按外键无关顺序清空全部业务表。
 */
public final class TableCleaner {

    private static final List<String> TABLES = List.of(
            "idempotent_request",
            "energy_command",
            "energy_budget",
            "daily_evaluation",
            "late_reading_event",
            "deviation_alert",
            "sensor_reading",
            "strategy_change_log",
            "zone_active_strategy",
            "climate_strategy",
            "crop",
            "zone");

    private TableCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            for (String table : TABLES) {
                jdbc.update("DELETE FROM " + table);
            }
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }
}
