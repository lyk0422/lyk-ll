package com.example.starter.radio.support;

import java.time.Instant;
import java.time.LocalDate;

import com.example.starter.StarterApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 集成测试基类：使用独立命名内存库 radio_test（MODE=MySQL），
 * 每个用例前清空业务表并重置可控时钟，避免用例间顺序依赖。
 */
@SpringBootTest(classes = StarterApplication.class)
@Import(TestClockConfig.class)
public abstract class IntegrationTestBase {

    /** 业务基准时刻：2026-09-26T00:00:00Z。 */
    protected static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");
    /** 日检日期（UTC 日历日）。 */
    protected static final LocalDate CHECK_DATE = LocalDate.of(2026, 9, 26);

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM reconciliation_ledger");
        jdbc.update("DELETE FROM dispensing_change_check");
        jdbc.update("DELETE FROM dispensing_change");
        jdbc.update("DELETE FROM dispensing_version");
        jdbc.update("DELETE FROM dispensing_check");
        jdbc.update("DELETE FROM dispensing");
        jdbc.update("DELETE FROM calibrator_daily_check");
        jdbc.update("DELETE FROM mother_liquor");
        jdbc.update("DELETE FROM prescription");
        jdbc.update("DELETE FROM dose_calibrator");
        clock.setInstant(Instant.parse("2026-09-26T08:00:00Z"));
    }

    protected static String unique(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID();
    }
}
