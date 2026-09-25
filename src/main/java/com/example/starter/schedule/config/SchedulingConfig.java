package com.example.starter.schedule.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用定时任务（漏报扫描）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
