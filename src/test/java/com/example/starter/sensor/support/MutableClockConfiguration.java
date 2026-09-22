package com.example.starter.sensor.support;

import com.example.starter.sensor.time.ServiceClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用可控墙钟：可手动推进到达时刻，避免对真实睡眠的依赖。
 */
@TestConfiguration
public class MutableClockConfiguration {

    /** 可手动设置的墙钟，初始为固定值。 */
    public static class MutableServiceClock implements ServiceClock {
        private volatile long current = 1_700_000_000_000L;

        @Override
        public long nowMillis() {
            return current;
        }

        public void setCurrent(long millis) {
            this.current = millis;
        }

        public void advanceMillis(long millis) {
            this.current += millis;
        }
    }

    @Bean
    @Primary
    public MutableServiceClock mutableServiceClock() {
        return new MutableServiceClock();
    }
}
