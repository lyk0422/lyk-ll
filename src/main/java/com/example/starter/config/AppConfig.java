package com.example.starter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.starter.support.ServiceClock;

/**
 * 基础 Bean 配置。
 */
@Configuration
public class AppConfig {

    @Bean
    public ServiceClock serviceClock() {
        return new ServiceClock();
    }
}
