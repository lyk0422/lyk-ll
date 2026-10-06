package com.example.starter;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import com.example.starter.time.TimeProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：提供可推进的 UTC 业务日期，覆盖系统时钟。
 */
@TestConfiguration
public class TestClockConfig {

    /**
     * 可变业务日期提供者。
     */
    public static class MutableTimeProvider implements TimeProvider {

        private final AtomicReference<LocalDate> today;

        public MutableTimeProvider(LocalDate initial) {
            this.today = new AtomicReference<>(initial);
        }

        @Override
        public LocalDate today() {
            return today.get();
        }

        public void set(LocalDate date) {
            today.set(date);
        }
    }

    @Bean
    @Primary
    public MutableTimeProvider mutableTimeProvider() {
        return new MutableTimeProvider(LocalDate.of(2026, 10, 6));
    }
}
