package com.example.starter.route.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 路由服务基础配置：提供系统时钟 Bean，测试可替换为固定时钟。
 */
@Configuration
public class RouteConfig {

    @Bean
    public Clock clock() {
        return Clock.system(java.time.ZoneId.of("Asia/Shanghai"));
    }
}
