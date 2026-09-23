package com.example.starter.calibration.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.calibration.service.CalibrationService;
import com.example.starter.calibration.support.TimeProvider;

/**
 * H2 集成测试基类：每个场景前清空全部业务表并固定时钟，保证场景间无顺序依赖。
 */
public abstract class AbstractCalibrationIntegrationTest {

    protected static final Instant FIXED_NOW = Instant.parse("2026-06-01T00:00:00Z");

    @Autowired
    protected CalibrationService service;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TimeProvider timeProvider;

    @BeforeEach
    void resetDatabaseAndClock() {
        jdbc.update("DELETE FROM resolution_record");
        jdbc.update("DELETE FROM active_canary_vehicle");
        jdbc.update("DELETE FROM canary_vehicle");
        jdbc.update("DELETE FROM canary_release");
        jdbc.update("DELETE FROM calibration_version");
        timeProvider.setClock(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    protected int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
