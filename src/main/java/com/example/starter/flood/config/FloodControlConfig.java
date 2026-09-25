package com.example.starter.flood.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 调洪服务 Bean 配置；时钟使用系统默认时区（Asia/Shanghai），
 * 测试可注入固定/偏移 Clock 验证签收超时等时间边界。
 */
@Configuration
@EnableConfigurationProperties(FloodControlProperties.class)
public class FloodControlConfig {

    @Bean
    public Clock floodClock() {
        return Clock.system(java.time.ZoneId.of("Asia/Shanghai"));
    }
}
