package com.example.starter.waste;

import javax.sql.DataSource;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 测试上下文关闭时释放命名内存库（DB_CLOSE_DELAY=-1 仅保证 JVM 内留存，不提供跨进程持久化）。
 */
@Configuration
public class TestDatabaseShutdownConfig {

    /**
     * 上下文关闭时执行 H2 SHUTDOWN，尽力释放测试库；连接池已先关闭时忽略失败。
     */
    @Bean
    public DisposableBean h2TestDatabaseShutdown(DataSource dataSource) {
        return () -> {
            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 连接池已关闭或库已释放时忽略
            }
        };
    }
}
