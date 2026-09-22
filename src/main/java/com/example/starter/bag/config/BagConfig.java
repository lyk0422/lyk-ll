package com.example.starter.bag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * rosbag 业务配置注册。
 */
@Configuration
@EnableConfigurationProperties(BagProperties.class)
public class BagConfig {
}
