package com.example.starter.museum.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * 博物馆借展领域 Bean 配置。
 * 定时升级默认开启；隔离测试中以 museum.scheduling.enabled=false 关闭后台线程，
 * 24 小时边界改由业务入口显式驱动，保证确定性。
 */
@Configuration
public class MuseumConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public ControllableClock clock() {
        return new ControllableClock();
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "museum.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
