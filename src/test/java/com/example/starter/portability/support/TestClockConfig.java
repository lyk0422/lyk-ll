package com.example.starter.portability.support;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextClosedEvent;

/**
 * 测试时钟配置：以可控时钟替换系统 UTC 时钟；
 * 测试上下文关闭时释放命名内存库（DB_CLOSE_DELAY=-1 不提供跨进程持久化）。
 */
@TestConfiguration
public class TestClockConfig {

    /** 测试基准时刻：2026-09-25T10:00:00Z（UTC，生效窗口外）。 */
    public static final Instant BASE_INSTANT = Instant.parse("2026-09-25T10:00:00Z");

    private static final Logger log = LoggerFactory.getLogger(TestClockConfig.class);

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_INSTANT);
    }

    @Bean
    public ApplicationListener<ContextClosedEvent> h2ShutdownListener(DataSource dataSource) {
        return event -> {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (Exception ex) {
                log.debug("测试 H2 数据库关闭时出现异常（可忽略）：{}", ex.getMessage());
            }
        };
    }
}
