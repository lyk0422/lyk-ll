package com.example.starter.perception.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 感知轨迹模块配置装配。
 */
@Configuration
@EnableConfigurationProperties(TrackProperties.class)
public class PerceptionConfig {
}
