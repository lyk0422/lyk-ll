package com.example.starter.rosbridge.config;

import com.example.starter.rosbridge.gateway.GatewayProperties;
import com.example.starter.rosbridge.gateway.GatewayService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 网关装配：启用配置属性绑定。
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig {
}
