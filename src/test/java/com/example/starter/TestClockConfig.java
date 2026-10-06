package com.example.starter;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：提供可推进的 MutableClock，替代系统时钟，
 * 使迟到取消等时间相关分支可控。
 */
@TestConfiguration
public class TestClockConfig {

    /** 可手动设置/推进的时钟。 */
    public static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        public MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        public void setNow(LocalDateTime dateTime) {
            this.instant = dateTime.atZone(zone).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 10, 6, 8, 0, 0);

    private static final MutableClock MUTABLE_CLOCK =
            new MutableClock(BASE_TIME.atZone(ZONE).toInstant(), ZONE);

    /** 全局共享的可控时钟，测试用例在前置方法中重置。 */
    public static MutableClock mutableClock() {
        return MUTABLE_CLOCK;
    }

    @Bean
    @Primary
    public Clock testClock() {
        return MUTABLE_CLOCK;
    }
}
