package com.example.starter.torque;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * H2(MODE=MySQL) 集成测试基类：每个用例前清空业务表并复位为固定 UTC 时钟。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractIntegrationTest {

    protected static final Instant BASE = Instant.parse("2026-09-26T08:00:00Z");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ServiceClock clock;

    @Autowired
    protected TorqueService service;

    @BeforeEach
    void resetEnv() {
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM late_measurement");
        jdbc.update("DELETE FROM witness_event");
        jdbc.update("DELETE FROM inspection_sign");
        jdbc.update("DELETE FROM rework_record");
        jdbc.update("DELETE FROM tightening_record");
        jdbc.update("DELETE FROM fastener_spec");
        jdbc.update("DELETE FROM station_active_card");
        jdbc.update("DELETE FROM work_card");
        jdbc.update("DELETE FROM torque_tool");
        setTime(BASE);
    }

    protected void setTime(Instant instant) {
        clock.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    protected void advanceSeconds(long seconds) {
        setTime(clock.now().plusSeconds(seconds));
    }
}
