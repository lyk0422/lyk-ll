package com.example.starter;

import java.time.Instant;

import javax.sql.DataSource;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试配置：可控时钟替换系统时钟；测试上下文关闭时释放命名内存库（DB_CLOSE_DELAY=-1 不提供跨进程持久化）。
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Bean
    public DisposableBean testDatabaseShutdown(DataSource dataSource) {
        return () -> {
            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // JVM 退出阶段连接池可能已关闭，忽略清理失败
            }
        };
    }
}
