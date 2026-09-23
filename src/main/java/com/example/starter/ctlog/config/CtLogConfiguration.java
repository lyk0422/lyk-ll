package com.example.starter.ctlog.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 证书透明日志组件配置入口。
 */
@Configuration
@EnableConfigurationProperties(CtLogProperties.class)
public class CtLogConfiguration {
}
