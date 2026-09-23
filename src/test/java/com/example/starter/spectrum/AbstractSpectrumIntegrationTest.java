package com.example.starter.spectrum;

import com.example.starter.spectrum.config.SpectrumClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * H2 集成测试基类：固定可控时钟，逐用例清空全部业务表，避免顺序依赖。
 */
@SpringBootTest
public abstract class AbstractSpectrumIntegrationTest {

    /** 测试基准时刻：2026-01-01T00:00:00Z 的 epoch 毫秒。 */
    protected static final long T0 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected SpectrumClock clock;

    @BeforeEach
    void setUpSpectrum() {
        clock.setClock(Clock.fixed(Instant.ofEpochMilli(T0), ZoneOffset.UTC));
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM lease_event");
        jdbc.update("DELETE FROM lease_cell");
        jdbc.update("DELETE FROM lease_version");
        jdbc.update("DELETE FROM lease");
    }

    @AfterEach
    void resetClock() {
        clock.setClock(Clock.systemUTC());
    }

    /** 推进可控时钟到指定 epoch 毫秒。 */
    protected void advanceClockTo(long epochMillis) {
        clock.setClock(Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC));
    }
}
