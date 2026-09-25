package com.example.starter.radio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * 衰变公式纯函数测试：A(t) = A0 * 2^(-(t - t0) / T)。
 */
class DecayServiceTest {

    private final DecayService decay = new DecayService();

    @Test
    void halvesActivityAfterOneHalfLife() {
        // 合成核素 T=3600s：2200 Bq 经 1 个半衰期恰为 1100 Bq
        double result = decay.activityAt(2200.0, 0L, 3600L, 3600L);
        assertThat(result).isEqualTo(1100.0);
    }

    @Test
    void quartersActivityAfterTwoHalfLives() {
        double result = decay.activityAt(2200.0, 0L, 7200L, 3600L);
        assertThat(result).isEqualTo(550.0);
    }

    @Test
    void extrapolatesBackwardsForEarlierTime() {
        // 目标时刻早于标定时刻时按同一公式反推，活度增大
        double result = decay.activityAt(1100.0, 3600L, 0L, 3600L);
        assertThat(result).isEqualTo(2200.0);
    }

    @Test
    void computesDecayResidual() {
        double residual = decay.decayResidual(2200.0, 0L, 3600L, 3600L);
        assertThat(residual).isEqualTo(1100.0);
    }

    @Test
    void handlesFractionalHalfLives() {
        double result = decay.activityAt(1000.0, 0L, 1800L, 3600L);
        assertThat(result).isCloseTo(1000.0 / Math.sqrt(2.0), within(1e-9));
    }

    @Test
    void rejectsNonPositiveHalfLife() {
        assertThatThrownBy(() -> decay.activityAt(1000.0, 0L, 1L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
