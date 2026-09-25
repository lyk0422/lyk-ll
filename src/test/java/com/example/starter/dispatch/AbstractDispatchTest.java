package com.example.starter.dispatch;

import com.example.starter.StarterApplication;
import com.example.starter.dispatch.support.MutableClock;
import com.example.starter.dispatch.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

/**
 * 调度服务测试基类：完整 Spring 上下文 + 真实 H2（MySQL 兼容）事务与约束，
 * 每个场景前清空业务表并复位可控时钟，避免顺序依赖。
 */
@SpringBootTest(classes = StarterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestClockConfig.class)
abstract class AbstractDispatchTest {

    protected static final Instant BASE = Instant.parse("2026-09-25T10:00:00Z");

    @Autowired
    protected com.example.starter.dispatch.service.DispatchService service;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM alarm_event");
        jdbc.update("DELETE FROM gate_receipt");
        jdbc.update("DELETE FROM gate_occupancy");
        jdbc.update("DELETE FROM dispatch_order");
        jdbc.execute("ALTER TABLE alarm_event ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE gate_receipt ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE gate_occupancy ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE dispatch_order ALTER COLUMN id RESTART WITH 1");
        jdbc.update("UPDATE gate SET opening = 0, updated_at = CURRENT_TIMESTAMP");
        clock.set(BASE);
    }
}
