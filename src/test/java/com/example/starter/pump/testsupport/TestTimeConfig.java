package com.example.starter.pump.testsupport;

import com.example.starter.pump.core.TimeProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;

/**
 * 测试时钟配置：以固定时刻启动，业务测试可推进。
 */
@TestConfiguration
public class TestTimeConfig {

    @Bean
    @Primary
    public TimeProvider controlledTimeProvider() {
        return new ControlledTimeProvider(LocalDateTime.of(2026, 9, 26, 8, 0));
    }
}
