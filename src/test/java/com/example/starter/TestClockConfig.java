package com.example.starter;

import java.time.Instant;

import javax.sql.DataSource;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试配置：以可控时钟替换业务时钟；JVM 退出时释放命名内存库 pv_test。
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    /**
     * 测试上下文结束后释放 H2 命名内存库（DB_CLOSE_DELAY=-1 不提供跨进程持久化）。
     */
    @Bean
    public Object h2TestDatabaseReleaser(DataSource dataSource) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                new JdbcTemplate(dataSource).execute("SHUTDOWN");
            } catch (RuntimeException ignored) {
                // JVM 退出阶段忽略释放失败
            }
        }));
        return new Object();
    }
}
