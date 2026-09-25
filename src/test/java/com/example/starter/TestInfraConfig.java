package com.example.starter;

import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextClosedEvent;

/**
 * 测试基础设施：可控时钟替换业务时钟；测试上下文关闭时释放 H2 命名内存库。
 */
@TestConfiguration
public class TestInfraConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    public ApplicationListener<ContextClosedEvent> h2ShutdownListener(DataSource dataSource) {
        return event -> {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (Exception e) {
                LoggerFactory.getLogger(TestInfraConfig.class)
                        .debug("测试上下文关闭时释放 H2 数据库: {}", e.getMessage());
            }
        };
    }
}
