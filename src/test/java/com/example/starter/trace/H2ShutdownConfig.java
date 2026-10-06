package com.example.starter.trace;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试上下文关闭时释放 H2 命名内存库（DB_CLOSE_DELAY=-1 不会自动释放）。
 */
@TestConfiguration
public class H2ShutdownConfig {

    @Bean
    public ApplicationListener<ContextClosedEvent> h2ShutdownListener(JdbcTemplate jdbcTemplate) {
        return event -> {
            try {
                jdbcTemplate.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 上下文关闭阶段数据源可能已销毁，忽略
            }
        };
    }
}
