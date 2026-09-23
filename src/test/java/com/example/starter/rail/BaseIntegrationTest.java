package com.example.starter.rail;

import com.example.starter.rail.service.TimeSource;
import com.example.starter.rail.testsupport.MutableClock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * H2 集成测试基类：注入可控时钟，并在每个场景前清理全部业务表，避免顺序依赖。
 */
@SpringBootTest
@Import(BaseIntegrationTest.TestClockConfiguration.class)
public abstract class BaseIntegrationTest {

    /** 固定基准时刻：2026-01-01T00:00:00Z。 */
    protected static final long BASE = 1767225600000L;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("DELETE FROM request_event").update();
        jdbc.sql("DELETE FROM request_section").update();
        jdbc.sql("DELETE FROM possession_request").update();
        jdbc.sql("DELETE FROM idempotency_record").update();
        jdbc.sql("DELETE FROM plan_snapshot").update();
        jdbc.sql("DELETE FROM section_registry").update();
        clock.set(BASE);
    }

    @TestConfiguration
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    /** 以固定基准时刻为参照偏移构造业务时间。 */
    protected static long at(long offsetMillis) {
        return BASE + offsetMillis;
    }

    protected TimeSource timeSource() {
        return clock;
    }
}
