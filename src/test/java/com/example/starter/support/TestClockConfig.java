package com.example.starter.support;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;

import javax.sql.DataSource;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextClosedEvent;

/**
 * 测试配置：可控时钟替代系统时钟；测试上下文关闭时释放 H2 命名内存库。
 */
@TestConfiguration
public class TestClockConfig {

    /**
     * 可控业务时钟，默认起点 2026-09-26T08:00:00Z（UTC）。
     */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-09-26T08:00:00Z"));
    }

    /**
     * 测试上下文关闭后释放 DB_CLOSE_DELAY=-1 保持的 H2 内存库。
     */
    @Bean
    public ApplicationListener<ContextClosedEvent> h2ShutdownOnClose(DataSource dataSource) {
        return event -> {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 上下文关闭阶段忽略释放失败
            }
        };
    }
}
