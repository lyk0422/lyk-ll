package com.example.starter.crane;

import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 测试专用固定时钟：位于主应用扫描包内的普通 {@code @Configuration}，
 * 测试运行时会被组件扫描拾取并以 {@code @Primary} 覆盖生产时钟；
 * 该类只存在于测试 classpath，不会进入打包产物。
 */
@Configuration
public class TestClockConfig {

    /** 所有测试共享的固定服务端时刻（UTC）。 */
    public static final java.time.Instant FIXED_NOW =
            java.time.Instant.parse("2026-09-25T08:00:00Z");

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }
}
