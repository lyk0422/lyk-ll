package com.example.starter;

import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试配置：可控时钟覆盖生产 Clock；测试上下文关闭时释放 H2 内存库。
 */
@TestConfiguration
public class TestClockConfig {

    /** 测试基准时刻。 */
    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }

    /**
     * 测试上下文关闭时执行 SHUTDOWN，释放 DB_CLOSE_DELAY=-1 保留的内存库。
     */
    @Bean
    public DisposableBean h2Shutdown(DataSource dataSource) {
        return () -> {
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 上下文关闭阶段忽略清理异常
            }
        };
    }
}
