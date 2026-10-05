package com.example.starter.blend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可控时钟：固定业务时间起步，支持用例推进时间（清洗逾期等场景），
 * 每个用例前由测试基类复位，保证时间相关断言确定。
 */
@TestConfiguration
public class TestClockConfig {

    /** 固定业务时间：2026-09-25 18:00:00 Asia/Shanghai。 */
    public static final String FIXED_TIME_TEXT = "2026-09-25 18:00:00";

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-25T10:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final MutableClock MUTABLE = new MutableClock(FIXED_INSTANT, ZONE);

    @Bean
    @Primary
    public Clock fixedClock() {
        return MUTABLE;
    }

    /** 复位到固定业务时间，每个用例前调用。 */
    public static void resetClock() {
        MUTABLE.setInstant(FIXED_INSTANT);
    }

    /** 推进业务时间，用于清洗逾期等时间相关场景。 */
    public static void advance(Duration duration) {
        MUTABLE.advance(duration);
    }

    /** 可操控时钟：支持测试推进时间。 */
    static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        synchronized void setInstant(Instant value) {
            this.instant = value;
        }

        synchronized void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId targetZone) {
            return new MutableClock(instant, targetZone);
        }

        @Override
        public synchronized Instant instant() {
            return instant;
        }
    }
}
