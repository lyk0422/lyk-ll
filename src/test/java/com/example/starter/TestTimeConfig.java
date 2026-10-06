package com.example.starter;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试配置：可控时钟覆盖生产时钟；上下文关闭时释放 H2 命名内存库。
 */
@TestConfiguration
public class TestTimeConfig {

    static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_INSTANT, ZoneOffset.UTC);
    }

    @Bean
    public DisposableBean testDatabaseShutdown(DataSource dataSource) {
        return () -> {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 上下文关闭阶段数据库可能已释放，忽略
            }
        };
    }
}
