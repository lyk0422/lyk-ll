package com.example.starter.hazmat.config;

import com.example.starter.hazmat.clock.MutableClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 公共 Bean 配置：可控时钟。
 */
@Configuration
public class HazmatConfig {

    @Bean
    public MutableClock mutableClock() {
        return new MutableClock();
    }
}
