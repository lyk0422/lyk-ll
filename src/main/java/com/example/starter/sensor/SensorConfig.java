package com.example.starter.sensor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 传感器同步模块配置：注册同步参数属性绑定。
 */
@Configuration
@EnableConfigurationProperties(SyncProperties.class)
public class SensorConfig {
}
