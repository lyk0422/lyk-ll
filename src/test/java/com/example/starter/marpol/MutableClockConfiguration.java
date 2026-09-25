package com.example.starter.marpol;

import com.example.starter.marpol.support.TimeService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 可控时钟：默认固定在 2026-09-10T00:00:00Z，测试可随时推进。
 */
@TestConfiguration
public class MutableClockConfiguration {

    /**
     * 可在测试中修改当前时刻的时间服务。
     */
    public static class MutableTimeService extends TimeService {

        private volatile Clock current;

        public MutableTimeService() {
            super(Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC));
            this.current = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC);
        }

        public void setInstant(Instant instant) {
            this.current = Clock.fixed(instant, ZoneOffset.UTC);
        }

        @Override
        public Instant now() {
            return Instant.now(current);
        }
    }

    @Bean
    @Primary
    public MutableTimeService mutableTimeService() {
        return new MutableTimeService();
    }
}
