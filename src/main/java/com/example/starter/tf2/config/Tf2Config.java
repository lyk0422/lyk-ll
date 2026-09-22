package com.example.starter.tf2.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * TF2 模块配置：启用缓存参数绑定。
 */
@Configuration
@EnableConfigurationProperties(Tf2CacheProperties.class)
public class Tf2Config {
}
