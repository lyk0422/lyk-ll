package com.example.starter.dispatch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度服务配置：启用定时超时扫描。
 */
@Configuration
@EnableScheduling
public class DispatchConfiguration {
}
