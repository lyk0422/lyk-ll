package com.example.starter.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 业务 Bean 配置：可控时钟、参数绑定与定时任务开关。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(CementProperties.class)
public class AppConfig {

    @Bean
    public ApplicationClock applicationClock() {
        return new ApplicationClock();
    }

    @Bean
    public Clock clock(ApplicationClock applicationClock) {
        return applicationClock;
    }
}
