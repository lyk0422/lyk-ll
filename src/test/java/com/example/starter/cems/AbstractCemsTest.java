package com.example.starter.cems;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.testsupport.TestClockConfiguration;

/**
 * H2（MODE=MySQL）集成测试基类：每个用例前清空数据并重置可控时钟到 2026-09-20 08:00（上海时区）。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
public abstract class AbstractCemsTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetEnvironment() {
        jdbc.update("DELETE FROM idempotent_event");
        jdbc.update("DELETE FROM outfall_event");
        jdbc.update("DELETE FROM restriction_order");
        jdbc.update("DELETE FROM exceedance_mark");
        jdbc.update("DELETE FROM missing_notice");
        jdbc.update("DELETE FROM monitor_reading");
        jdbc.update("DELETE FROM outfall");
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T08:00:00");
    }
}
