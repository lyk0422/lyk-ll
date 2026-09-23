package com.example.starter.costmap.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 膨胀代价公式测试：致命/内切/指数衰减/越界四段语义。
 */
class InflationTest {

    @Test
    void lethalAtZeroDistance() {
        assertThat(Inflation.inflateCost(0.0, 1.0, 3.0, 2.0)).isEqualTo(254);
    }

    @Test
    void inscribedWithinInscribedRadius() {
        assertThat(Inflation.inflateCost(0.5, 1.0, 3.0, 2.0)).isEqualTo(253);
        assertThat(Inflation.inflateCost(1.0, 1.0, 3.0, 2.0)).isEqualTo(253);
    }

    @Test
    void decayMonotonicallyBetweenRadii() {
        int prev = Integer.MAX_VALUE;
        for (int d = 2; d <= 6; d++) {
            int cost = Inflation.inflateCost(d, 1.0, 6.0, 1.0);
            assertThat(cost).isBetween(1, 252);
            assertThat(cost).isLessThan(prev);
            prev = cost;
        }
    }

    @Test
    void zeroOutsideInflationRadius() {
        assertThat(Inflation.inflateCost(3.01, 1.0, 3.0, 2.0)).isZero();
        assertThat(Inflation.inflateCost(10.0, 1.0, 3.0, 2.0)).isZero();
    }

    @Test
    void higherScalingFactorDecaysFaster() {
        int slow = Inflation.inflateCost(2.0, 1.0, 5.0, 0.5);
        int fast = Inflation.inflateCost(2.0, 1.0, 5.0, 3.0);
        assertThat(fast).isLessThan(slow);
    }
}
