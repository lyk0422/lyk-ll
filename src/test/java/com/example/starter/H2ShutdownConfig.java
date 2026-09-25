package com.example.starter;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试上下文关闭时主动 SHUTDOWN 命名内存库 vaccine_test，
 * 释放 DB_CLOSE_DELAY=-1 保活的 H2 数据库。
 */
@TestConfiguration
public class H2ShutdownConfig {

    private final JdbcTemplate jdbc;

    public H2ShutdownConfig(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PreDestroy
    void shutdownH2() {
        try {
            jdbc.execute("SHUTDOWN");
        } catch (Exception ignored) {
            // 库已随连接关闭时无需处理
        }
    }
}
