package com.example.starter.bonded;

import com.example.starter.bonded.support.TimeProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用可控时钟：可前进/设置当前 UTC 时间，用于验证 48 小时边界。
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableTimeProvider mutableTimeProvider() {
        return new MutableTimeProvider(Instant.parse("2026-01-01T00:00:00Z"));
    }

    /**
     * 可变的 TimeProvider。
     */
    public static class MutableTimeProvider extends TimeProvider {

        private final AtomicReference<Instant> current;

        public MutableTimeProvider(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        @Override
        public Instant now() {
            return current.get();
        }

        public void set(Instant instant) {
            current.set(instant);
        }

        public void advanceSeconds(long seconds) {
            current.updateAndGet(t -> t.plusSeconds(seconds));
        }
    }
}
