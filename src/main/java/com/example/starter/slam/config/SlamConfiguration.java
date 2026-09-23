package com.example.starter.slam.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * SLAM 后端配置：启用版本化回环阈值属性绑定。
 */
@Configuration
@EnableConfigurationProperties(LoopThresholdProperties.class)
public class SlamConfiguration {
}
