package com.example.starter.rosbridge.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 网关配置：启用配置属性绑定。
 */
@Configuration
@EnableConfigurationProperties(RosBridgeProperties.class)
public class RosBridgeConfig {
}
