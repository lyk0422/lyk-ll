package com.example.starter.irrigation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextClosedEvent;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 测试数据库释放配置：测试上下文关闭时关闭命名 H2 内存库（DB_CLOSE_DELAY=-1 不提供跨进程持久化）。
 */
@TestConfiguration
public class TestDatabaseReleaseConfig {

    @Bean
    ApplicationListener<ContextClosedEvent> h2ShutdownListener(DataSource dataSource) {
        return event -> {
            try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
                st.execute("SHUTDOWN");
            } catch (SQLException ignored) {
                // 上下文关闭阶段忽略释放异常。
            }
        };
    }
}
