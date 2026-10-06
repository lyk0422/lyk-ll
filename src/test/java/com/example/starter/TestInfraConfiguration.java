package com.example.starter;

import java.time.LocalDateTime;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.config.ClockConfig;

/**
 * 测试基础设施：可控时钟（@Primary 覆盖系统时钟）与测试库释放。
 */
@TestConfiguration
public class TestInfraConfiguration {

    /** 测试基准时间：2026-10-06 00:00 Asia/Shanghai。 */
    public static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 10, 6, 0, 0);

    /**
     * 可控时钟：@Primary 覆盖应用系统时钟，业务注入 Clock 处得到本实例。
     */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_TIME, ClockConfig.BUSINESS_ZONE);
    }

    /**
     * 测试上下文关闭时释放 H2 命名内存库（DB_CLOSE_DELAY=-1 不会自动释放）。
     */
    @Bean
    public DisposableBean testDatabaseShutdown(JdbcTemplate jdbcTemplate) {
        return () -> {
            try {
                jdbcTemplate.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 连接池可能已关闭，忽略释放失败
            }
        };
    }
}
