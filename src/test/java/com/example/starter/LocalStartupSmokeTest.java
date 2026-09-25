package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地启动入口冒烟（默认 profile，即不激活 test）：
 * 验证嵌入式 H2 自动建表与启动合成数据初始化，无需 MySQL/Docker。
 */
@SpringBootTest
class LocalStartupSmokeTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void schema_initialized_and_synthetic_data_ready() {
        Integer siloCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM silo WHERE code IN ('SILO-A1', 'SILO-A2')", Integer.class);
        assertEquals(2, siloCount, "启动后应预置两座合成粮仓");

        Integer fanCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fan WHERE code IN ('FAN-01', 'FAN-02')", Integer.class);
        assertEquals(2, fanCount, "启动后应预置两台合成风机");

        String status = jdbc.queryForObject(
                "SELECT status FROM silo WHERE code = 'SILO-A1'", String.class);
        assertEquals("MONITORING", status, "新粮仓初始处于日常监测态");

        // 幂等台账表随 schema 自动建好
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request", Integer.class) != null);
    }
}
