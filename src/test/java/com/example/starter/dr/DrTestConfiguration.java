package com.example.starter.dr;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.PreDestroy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 测试统一配置：固定 UTC 时钟保证时间断言可控；上下文关闭时主动 SHUTDOWN
 * 释放带 DB_CLOSE_DELAY=-1 的命名内存库，避免跨上下文残留。
 */
@Configuration
public class DrTestConfiguration {

    private final JdbcTemplate jdbc;

    public DrTestConfiguration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Bean
    @Primary
    public Clock drClock() {
        return Clock.fixed(Instant.parse("2026-09-23T03:00:00Z"), ZoneOffset.UTC);
    }

    @PreDestroy
    void shutdownH2() {
        try {
            jdbc.execute("SHUTDOWN");
        } catch (RuntimeException ignored) {
            // H2 可能已被关闭钩子释放，无需处理。
        }
    }
}
