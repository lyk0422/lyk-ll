package com.example.starter.cems.testsupport;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：用固定起点的可控时钟替换系统时钟，覆盖“恰满 2 小时”等边界判定。
 */
@TestConfiguration
public class TestClockConfiguration {

    public static final ControlledClock CLOCK =
            new ControlledClock(java.time.LocalDateTime.of(2026, 9, 20, 8, 0)
                    .atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                    ZoneId.of("Asia/Shanghai"));

    @Bean
    @Primary
    public Clock cemsTestClock() {
        return CLOCK;
    }
}
