package com.example.starter.rebate;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 业务测试基类：隔离 H2 内存库（application-test.yaml），每场景清表并重置自增，
 * 使用固定 UTC 时钟保证时间相关行为可复算。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@Import(RebateServiceTestBase.FixedClockConfig.class)
public abstract class RebateServiceTestBase {

    private static final List<String> TABLES = List.of(
            "late_event", "refund_registration", "idempotent_request",
            "declaration_registration", "doubt", "validation_detail",
            "rebate_batch", "rebate_document", "rate_library");

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        for (String table : TABLES) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
        jdbcTemplate.execute("ALTER TABLE rate_library ALTER COLUMN id RESTART WITH 1");
        jdbcTemplate.execute("ALTER TABLE rebate_document ALTER COLUMN id RESTART WITH 1");
        jdbcTemplate.execute("ALTER TABLE rebate_batch ALTER COLUMN id RESTART WITH 1");
        jdbcTemplate.execute("ALTER TABLE validation_detail ALTER COLUMN id RESTART WITH 1");
        jdbcTemplate.execute("ALTER TABLE doubt ALTER COLUMN id RESTART WITH 1");
        jdbcTemplate.execute("ALTER TABLE refund_registration ALTER COLUMN id RESTART WITH 1");
        jdbcTemplate.execute("ALTER TABLE late_event ALTER COLUMN id RESTART WITH 1");
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        public Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-25T08:00:00Z"), ZoneOffset.UTC);
        }
    }
}
