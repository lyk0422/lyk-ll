package com.example.starter;

import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 测试上下文关闭时释放 H2 命名内存库（测试库使用 DB_CLOSE_DELAY=-1，需显式 SHUTDOWN 释放）。
 * 仅存在于测试类路径，不影响主工程。
 */
@Configuration
public class TestDatabaseShutdown {

    @Bean
    ApplicationListener<ContextClosedEvent> h2ShutdownListener(DataSource dataSource) {
        return event -> {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 上下文关闭阶段数据源可能已不可用，忽略异常
            }
        };
    }
}
