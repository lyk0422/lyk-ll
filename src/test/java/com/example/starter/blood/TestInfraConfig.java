package com.example.starter.blood;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 测试配置：可控时钟替换系统时钟；上下文关闭时清空命名内存库，释放测试数据库。
 */
@TestConfiguration
public class TestInfraConfig {

    /**
     * 可控时钟（优先于生产 Clock bean 注入业务服务）。
     *
     * @return 可控时钟，默认固定在 2026-09-24T00:00:00Z
     */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-09-24T00:00:00Z"));
    }

    /**
     * 测试上下文关闭时清空命名内存库（DB_CLOSE_DELAY=-1 下 JVM 内常驻，需显式释放）。
     *
     * @param jdbc JDBC 客户端
     * @return 清理组件
     */
    @Bean
    public TestDatabaseCleaner testDatabaseCleaner(JdbcClient jdbc) {
        return new TestDatabaseCleaner(jdbc);
    }

    /**
     * 命名内存库清理组件。
     */
    public static final class TestDatabaseCleaner implements AutoCloseable {

        private final JdbcClient jdbc;

        TestDatabaseCleaner(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void close() {
            jdbc.sql("DROP ALL OBJECTS").update();
        }
    }
}
