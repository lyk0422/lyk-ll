package com.example.starter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务（公示期满自动生效）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
