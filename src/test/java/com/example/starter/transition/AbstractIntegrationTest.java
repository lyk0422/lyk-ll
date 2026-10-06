package com.example.starter.transition;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * H2（MODE=MySQL）集成测试基类：固定时钟，场景间清理全部业务表（保留合成品种主数据）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractIntegrationTest {

    protected static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-25T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final List<String> BUSINESS_TABLES = List.of(
            "late_event",
            "idempotent_request",
            "blend_control_event",
            "blend_reading",
            "blend_ratio_version",
            "roll_verdict_event",
            "roll_inspection",
            "transition_roll",
            "cleaning_window",
            "machine_active_slot",
            "transition_order");

    protected void cleanBusinessTables(JdbcTemplate jdbc) {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : BUSINESS_TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    @TestConfiguration
    public static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }
}
