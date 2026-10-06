package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

/**
 * 集成测试基类：每个用例前清空全部业务表并重建三个合成展示池，避免用例间顺序依赖。
 */
@SpringBootTest
public abstract class BaseIntegrationTest {

    /** 统一基准时间：2026-10-06 08:00:00 */
    protected static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 6, 8, 0, 0);

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM zone_event");
        jdbcTemplate.update("DELETE FROM disposal_command");
        jdbcTemplate.update("DELETE FROM alarm");
        jdbcTemplate.update("DELETE FROM water_sample");
        jdbcTemplate.update("DELETE FROM exhibition_registration");
        jdbcTemplate.update("DELETE FROM retest_record");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM zone_pool");
        insertZone("ZONE-A", "珊瑚礁展示区");
        insertZone("ZONE-B", "企鹅馆");
        insertZone("ZONE-C", "深海隧道");
    }

    private void insertZone(String code, String name) {
        jdbcTemplate.update("INSERT INTO zone_pool (code, name, status, consecutive_violations,"
                        + " sensor_state, last_heartbeat_at, missing_since, updated_at)"
                        + " VALUES (?, ?, 'NORMAL', 0, 'ONLINE', ?, NULL, ?)",
                code, name, T0, T0);
    }

    /**
     * 按编码查询展示池主键。
     */
    protected long zoneId(String code) {
        Long id = jdbcTemplate.queryForObject("SELECT id FROM zone_pool WHERE code = ?", Long.class, code);
        return id == null ? -1 : id;
    }

    /**
     * 按编码查询池状态。
     */
    protected String zoneStatus(String code) {
        return jdbcTemplate.queryForObject("SELECT status FROM zone_pool WHERE code = ?",
                String.class, code);
    }

    /**
     * 按编码查询传感器状态。
     */
    protected String sensorState(String code) {
        return jdbcTemplate.queryForObject("SELECT sensor_state FROM zone_pool WHERE code = ?",
                String.class, code);
    }
}
