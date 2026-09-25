package com.example.starter.pv.config;

import java.time.Clock;
import java.time.ZoneOffset;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用 Bean 配置；业务时间一律以 UTC 时钟为准。
 */
@Configuration
public class ClockConfig {

    /**
     * UTC 系统时钟，测试中以可控时钟替换。
     */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneOffset.UTC);
    }
}
