package com.example.starter.ili;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ILI 服务的基础设施配置；提供可替换的时钟，便于时间相关测试使用可控时钟。
 */
@Configuration
public class IliConfiguration {

    /**
     * 业务时钟，默认使用 UTC 系统时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
