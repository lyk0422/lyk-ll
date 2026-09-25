package com.example.starter.hazmat.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 合成路线主数据初始化（仅当 route 表为空时写入，全部为合成数据）：
 * R-LOW 通行等级 1；R-HIGH 通行等级 3；R-RESTRICTED 通行等级 3 且
 * 2026-01-01 08:00（含）至 12:00（不含）管制。途经点偏移均为 30/60/90 分钟。
 */
@Component
public class SyntheticDataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public SyntheticDataInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM route", Long.class);
        if (count != null && count > 0) {
            return;
        }
        insertRoute("R-LOW", "市区低危路线", 1);
        insertRoute("R-HIGH", "高速高危路线", 3);
        long restricted = insertRoute("R-RESTRICTED", "港区管制路线", 3);
        jdbc.update(
                "INSERT INTO route_restriction (route_id, start_time, end_time)"
                        + " VALUES (?, '2026-01-01 08:00:00', '2026-01-01 12:00:00')",
                restricted);
    }

    private long insertRoute(String code, String name, int passLevel) {
        jdbc.update("INSERT INTO route (code, name, pass_level) VALUES (?, ?, ?)",
                code, name, passLevel);
        Long id = jdbc.queryForObject("SELECT id FROM route WHERE code = ?", Long.class, code);
        for (int seq = 1; seq <= 3; seq++) {
            jdbc.update(
                    "INSERT INTO route_waypoint (route_id, seq, name, offset_minutes)"
                            + " VALUES (?, ?, ?, ?)",
                    id, seq, code + "-WP" + seq, seq * 30);
        }
        return id;
    }
}
