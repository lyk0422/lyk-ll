package com.example.starter.decay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * 衰变公式单元测试：A(t) = A0 * 2^(-(t - t0) / T)。
 */
class DecayServiceTest {

    private final DecayService decayService = new DecayService();

    @Test
    void 经过一个半衰期活度减半() {
        Instant t0 = Instant.parse("2026-09-26T00:00:00Z");
        Instant t1 = Instant.parse("2026-09-26T01:00:00Z");
        assertThat(decayService.decayTo(1000.0, t0, t1, 3600)).isEqualTo(500.0);
    }

    @Test
    void 经过两个半衰期活度为四分之一() {
        Instant t0 = Instant.parse("2026-09-26T00:00:00Z");
        Instant t2 = Instant.parse("2026-09-26T02:00:00Z");
        assertThat(decayService.decayTo(1000.0, t0, t2, 3600)).isEqualTo(250.0);
    }

    @Test
    void 不足一个半衰期按指数推算() {
        Instant t0 = Instant.parse("2026-09-26T00:00:00Z");
        Instant t1 = Instant.parse("2026-09-26T00:30:00Z");
        assertThat(decayService.decayTo(1000.0, t0, t1, 3600))
                .isCloseTo(1000.0 * Math.pow(2.0, -0.5), within(1e-9));
    }

    @Test
    void 同一时刻活度不变() {
        Instant t0 = Instant.parse("2026-09-26T00:00:00Z");
        assertThat(decayService.decayTo(1000.0, t0, t0, 3600)).isEqualTo(1000.0);
    }
}
