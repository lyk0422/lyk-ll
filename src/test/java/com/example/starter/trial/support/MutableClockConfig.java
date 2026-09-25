package com.example.starter.trial.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * 测试用可控 UTC 时钟：默认固定在一个合成时刻，测试可随时拨动，
 * 用于验证 UTC 日界判定与审计事件顺序。
 */
@TestConfiguration
public class MutableClockConfig {

    private static final Instant DEFAULT_NOW = Instant.parse("2026-03-10T08:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(DEFAULT_NOW);
    }

    /** 可在测试中显式设置的时钟实现。 */
    public static class MutableClock implements Supplier<Instant> {
        private volatile Instant now;

        public MutableClock(Instant now) {
            this.now = now;
        }

        public void set(Instant instant) {
            this.now = instant;
        }

        public void advanceSeconds(long seconds) {
            this.now = now.plusSeconds(seconds);
        }

        @Override
        public Instant get() {
            return now;
        }
    }
}
