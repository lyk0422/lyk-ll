package com.example.starter;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试上下文配置：共享 H2 命名内存库，上下文关闭时释放数据库。
 */
@TestConfiguration
public class TestDbConfig {

    @Bean
    public DisposableBean testDatabaseShutdown(JdbcTemplate jdbcTemplate) {
        return () -> {
            try {
                jdbcTemplate.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 数据库已关闭时忽略
            }
        };
    }
}
