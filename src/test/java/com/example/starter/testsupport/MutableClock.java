package com.example.starter.testsupport;

import com.example.starter.support.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试用可控时钟：可固定、拨动与快进，替代系统时钟。
 */
public class MutableClock implements Clock {

    private volatile Instant instant;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    @Override
    public Instant now() {
        return instant;
    }

    public void set(Instant instant) {
        this.instant = instant;
    }

    /** 时钟向前推进指定秒数。 */
    public void advanceSeconds(long seconds) {
        this.instant = instant.plusSeconds(seconds);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        }
    }
}
