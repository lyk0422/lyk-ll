package com.example.starter.cutover.support;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置。生产使用系统默认时区（Asia/Shanghai）时钟；
 * 并发/超时测试可注入固定或偏移时钟实现可控时间。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
