package com.example.starter.ventilation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.starter.ventilation.TimeService;

/**
 * 通风联动相关 Bean：统一可控时钟。
 */
@Configuration
public class VentilationConfig {

    @Bean
    public TimeService timeService() {
        return new TimeService();
    }
}
