package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.flood.domain.CapacityJudgment;
import com.example.starter.flood.domain.DangerJudgment;
import com.example.starter.flood.domain.IndicatorJudgment;
import com.example.starter.flood.service.RoutingJudgment;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 调洪判定边界口径单测：库容恰等临界、双指标任一判险/恰等临界。
 */
class RoutingJudgmentTest {

    private static final BigDecimal AVAILABLE = new BigDecimal("400000");
    private static final BigDecimal LIMIT = new BigDecimal("100");

    @Test
    @DisplayName("库容：设计洪水量小于可用库容判安全")
    void capacitySafe() {
        assertThat(RoutingJudgment.capacity(new BigDecimal("399999"), AVAILABLE))
                .isEqualTo(CapacityJudgment.SAFE);
    }

    @Test
    @DisplayName("库容：设计洪水量恰等于可用库容判临界")
    void capacityCriticalWhenExactlyEqual() {
        assertThat(RoutingJudgment.capacity(new BigDecimal("400000"), AVAILABLE))
                .isEqualTo(CapacityJudgment.CRITICAL);
    }

    @Test
    @DisplayName("库容：设计洪水量超出可用库容判须泄流")
    void capacityReleaseRequired() {
        assertThat(RoutingJudgment.capacity(new BigDecimal("400001"), AVAILABLE))
                .isEqualTo(CapacityJudgment.RELEASE_REQUIRED);
    }

    @Test
    @DisplayName("指标：高于下限安全、恰等下限临界、低于下限险")
    void indicatorBoundaries() {
        assertThat(RoutingJudgment.indicator(new BigDecimal("101"), LIMIT))
                .isEqualTo(IndicatorJudgment.SAFE);
        assertThat(RoutingJudgment.indicator(new BigDecimal("100"), LIMIT))
                .isEqualTo(IndicatorJudgment.CRITICAL);
        assertThat(RoutingJudgment.indicator(new BigDecimal("99"), LIMIT))
                .isEqualTo(IndicatorJudgment.DANGER);
        assertThat(RoutingJudgment.indicator(null, LIMIT))
                .isEqualTo(IndicatorJudgment.SAFE);
    }

    @Test
    @DisplayName("综合：干滩、浸润线任一低于下限即险，另一指标安全不抵消")
    void combineAnyIndicatorDanger() {
        DangerJudgment one = RoutingJudgment.combine(CapacityJudgment.SAFE,
                IndicatorJudgment.DANGER, IndicatorJudgment.SAFE);
        DangerJudgment other = RoutingJudgment.combine(CapacityJudgment.SAFE,
                IndicatorJudgment.SAFE, IndicatorJudgment.DANGER);
        assertThat(one).isEqualTo(DangerJudgment.DANGER);
        assertThat(other).isEqualTo(DangerJudgment.DANGER);
    }

    @Test
    @DisplayName("综合：任一指标恰等下限，综合判临界")
    void combineCriticalIndicator() {
        DangerJudgment j = RoutingJudgment.combine(CapacityJudgment.SAFE,
                IndicatorJudgment.CRITICAL, IndicatorJudgment.SAFE);
        assertThat(j).isEqualTo(DangerJudgment.CRITICAL);
    }

    @Test
    @DisplayName("综合：库容须泄流且指标临界时，险情优先；仅须泄流时判须泄流")
    void combineCapacityPrecedence() {
        assertThat(RoutingJudgment.combine(CapacityJudgment.RELEASE_REQUIRED,
                IndicatorJudgment.SAFE, IndicatorJudgment.SAFE))
                .isEqualTo(DangerJudgment.RELEASE_REQUIRED);
        assertThat(RoutingJudgment.combine(CapacityJudgment.RELEASE_REQUIRED,
                IndicatorJudgment.DANGER, IndicatorJudgment.SAFE))
                .isEqualTo(DangerJudgment.DANGER);
        assertThat(RoutingJudgment.combine(CapacityJudgment.CRITICAL,
                IndicatorJudgment.SAFE, IndicatorJudgment.SAFE))
                .isEqualTo(DangerJudgment.CRITICAL);
        assertThat(RoutingJudgment.combine(CapacityJudgment.SAFE,
                IndicatorJudgment.SAFE, IndicatorJudgment.SAFE))
                .isEqualTo(DangerJudgment.SAFE);
    }
}
