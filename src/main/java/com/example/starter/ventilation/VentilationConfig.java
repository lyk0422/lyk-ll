package com.example.starter.ventilation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通风联动服务 Bean 装配。
 */
@Configuration
@EnableConfigurationProperties(VentilationProperties.class)
public class VentilationConfig {

    @Bean
    public MutableClock mutableClock() {
        return new MutableClock();
    }
}
