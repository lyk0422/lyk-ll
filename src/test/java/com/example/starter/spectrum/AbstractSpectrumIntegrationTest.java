package com.example.starter.spectrum;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * H2 集成测试基类：固定 UTC 时钟保证时间相关断言可控，场景间清空业务表避免顺序依赖。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractSpectrumIntegrationTest {

    /** 测试固定"当前时刻"（UTC）：租约窗口与快照时刻均围绕它构造。 */
    public static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM lease_event");
        jdbcTemplate.update("DELETE FROM lease_region");
        jdbcTemplate.update("DELETE FROM lease_version");
        jdbcTemplate.update("DELETE FROM lease");
        jdbcTemplate.update("DELETE FROM lease_request");
    }

    /** 用固定时钟替换生产系统时钟。 */
    @TestConfiguration
    public static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedSpectrumClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
