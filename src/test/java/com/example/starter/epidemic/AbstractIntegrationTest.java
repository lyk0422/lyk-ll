package com.example.starter.epidemic;

import com.example.starter.epidemic.support.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * H2（MODE=MySQL）集成测试基类：每个用例前清空业务数据并固定 UTC 时钟。
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    protected static final Instant FIXED_NOW = Instant.parse("2026-09-25T08:00:00Z");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TimeProvider timeProvider;

    @BeforeEach
    void cleanAndResetClock() {
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM card_version");
        jdbc.update("DELETE FROM special_correction");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM epidemic_card");
        jdbc.update("DELETE FROM card_number_seq");
        timeProvider.setClock(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }
}
