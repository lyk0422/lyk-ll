package com.example.starter.support;

import java.time.Instant;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试配置：以可控时钟覆盖业务时间源；测试上下文关闭时释放命名内存库。
 */
@TestConfiguration
public class TestClockConfig {

    /** 测试基准时刻：2026-10-06T00:00:00Z（Asia/Shanghai 当日 08:00）。 */
    public static final Instant BASE_INSTANT = Instant.parse("2026-10-06T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_INSTANT, ZoneOffset.UTC);
    }

    /**
     * 上下文关闭后对测试内存库执行 SHUTDOWN，避免 DB_CLOSE_DELAY=-1 的库悬挂到 JVM 退出。
     */
    @Bean
    public DisposableBean h2TestDatabaseShutdown(DataSource dataSource) {
        return () -> {
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 数据库已关闭时忽略
            }
        };
    }
}
