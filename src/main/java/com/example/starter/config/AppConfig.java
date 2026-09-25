package com.example.starter.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 通用 Bean 配置：可控时钟（测试可替换为固定时钟）与定时调度（指令超时升级扫描）。
 */
@Configuration
@EnableScheduling
@ConfigurationPropertiesScan
public class AppConfig {

    /** 业务统一使用 Asia/Shanghai 时区进行小时归位与日结切日。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock businessClock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
