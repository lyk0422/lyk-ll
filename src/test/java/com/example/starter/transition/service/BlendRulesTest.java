package com.example.starter.transition.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回抄浆掺配比例纯规则单元测试：允许区间边界、偏差容差边界、失控连击、调整方式判定。
 */
class BlendRulesTest {

    @Test
    @DisplayName("掺配比例恰在合成允许区间边界（5.000/30.000）合法，越界拒绝")
    void blendRatioRangeBoundariesInclusive() {
        assertTrue(TransitionRules.withinBlendRatioRange(new BigDecimal("5.000")), "下边界合法");
        assertTrue(TransitionRules.withinBlendRatioRange(new BigDecimal("30.000")), "上边界合法");
        assertTrue(TransitionRules.withinBlendRatioRange(new BigDecimal("17.500")));
        assertFalse(TransitionRules.withinBlendRatioRange(new BigDecimal("4.999")));
        assertFalse(TransitionRules.withinBlendRatioRange(new BigDecimal("30.001")));
    }

    @Test
    @DisplayName("偏差恰 2.0000% 合格，超过才超差；偏差取绝对值保留 4 位")
    void blendDeviationToleranceBoundary() {
        BigDecimal deviation = TransitionRules.blendDeviationPct(
                new BigDecimal("22.000"), new BigDecimal("20.000"));
        assertEquals(0, new BigDecimal("2.0000").compareTo(deviation));
        assertTrue(TransitionRules.withinBlendTolerance(deviation), "恰 2% 合格");

        BigDecimal beyond = TransitionRules.blendDeviationPct(
                new BigDecimal("22.001"), new BigDecimal("20.000"));
        assertEquals(0, new BigDecimal("2.0010").compareTo(beyond));
        assertFalse(TransitionRules.withinBlendTolerance(beyond), "超过 2% 超差");

        // 负向偏差同样按绝对值判定。
        BigDecimal negative = TransitionRules.blendDeviationPct(
                new BigDecimal("18.000"), new BigDecimal("20.000"));
        assertEquals(0, new BigDecimal("2.0000").compareTo(negative));
        assertTrue(TransitionRules.withinBlendTolerance(negative));
    }

    @Test
    @DisplayName("连续超差达 3 个采样点判失控，2 个不判")
    void outOfControlStreak() {
        assertFalse(TransitionRules.isBlendOutOfControl(0));
        assertFalse(TransitionRules.isBlendOutOfControl(2));
        assertTrue(TransitionRules.isBlendOutOfControl(3), "连续 3 点超差判失控");
        assertTrue(TransitionRules.isBlendOutOfControl(4));
    }

    @Test
    @DisplayName("相邻版本比例差恰 5.0000% 在线直调，超出须降速调整")
    void blendAdjustModeBoundary() {
        assertTrue(TransitionRules.isDirectBlendAdjust(new BigDecimal("5.0000")), "恰 5% 在线直调");
        assertFalse(TransitionRules.isDirectBlendAdjust(new BigDecimal("5.0001")), "超出须降速调整");
        assertTrue(TransitionRules.isDirectBlendAdjust(new BigDecimal("0.0000")));
    }
}
