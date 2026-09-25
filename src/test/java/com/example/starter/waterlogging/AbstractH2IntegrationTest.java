package com.example.starter.waterlogging;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2（MODE=MySQL）集成测试基类：每个用例前清空业务流水表并将合成泵站复位为停机、闸门全关，
 * 避免用例间顺序依赖；基础台账（预警级别、泵站）保持 data.sql 合成数据不变。
 */
@SpringBootTest
@Import(AbstractH2IntegrationTest.FixedClockConfiguration.class)
public abstract class AbstractH2IntegrationTest {

    /** 固定可控时钟：2026-09-26 08:00:00 +08:00。 */
    protected static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-26T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    /** 有先后依赖时按子表先删的顺序清空。 */
    private static final List<String> CLEAR_TABLES = List.of(
            "idempotent_request",
            "review_archive",
            "loss_contact_order",
            "late_level_event",
            "water_level_reading",
            "pump_command",
            "zone_sequence_lock",
            "pre_drain_plan");

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        CLEAR_TABLES.forEach(table -> jdbcTemplate.update("DELETE FROM " + table));
        jdbcTemplate.update("UPDATE pump_station SET run_status = 'STOPPED', gate_opening = 0");
    }

    /** 暴露固定时钟的测试配置：以不同 bean 名注册并标记 @Primary 优先于生产系统时钟。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class FixedClockConfiguration {

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        Clock fixedWaterClock() {
            return FIXED_CLOCK;
        }
    }
}
