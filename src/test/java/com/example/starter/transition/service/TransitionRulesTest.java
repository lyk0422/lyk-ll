package com.example.starter.transition.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改品种纯规则单元测试：克重差、灰分级差、工艺窗口边界、过渡料长度、正品门槛边界。
 */
class TransitionRulesTest {

    @Test
    @DisplayName("克重差恰 10%（70→77）判为直切，超出（70→80）须停机清洗")
    void directSwitchBoundaryAtExactlyTenPercent() {
        BigDecimal exactTen = TransitionRules.basisWeightDeltaPct(new BigDecimal("70.00"),
                new BigDecimal("77.00"));
        assertEquals(0, new BigDecimal("10.0000").compareTo(exactTen));
        assertTrue(TransitionRules.isDirectSwitch(exactTen), "恰 10% 必须直切");

        BigDecimal beyond = TransitionRules.basisWeightDeltaPct(new BigDecimal("70.00"),
                new BigDecimal("80.00"));
        assertEquals(0, new BigDecimal("14.2857").compareTo(beyond));
        assertFalse(TransitionRules.isDirectSwitch(beyond), "超出 10% 须停机清洗");

        // 略低于 10% 也直切。
        BigDecimal below = TransitionRules.basisWeightDeltaPct(new BigDecimal("77.00"),
                new BigDecimal("84.00"));
        assertEquals(0, new BigDecimal("9.0909").compareTo(below));
        assertTrue(TransitionRules.isDirectSwitch(below));
    }

    @Test
    @DisplayName("灰分等级差恰 2 级合法直切不冲洗，超过 2 级须冲洗网部")
    void ashLevelBoundaryAtExactlyTwo() {
        assertEquals(2, TransitionRules.ashLevelDelta(1, 3));
        assertFalse(TransitionRules.needsWireFlush(2), "恰 2 级不得冲洗网部");
        assertTrue(TransitionRules.needsWireFlush(3), "差 3 级须冲洗网部");
        assertFalse(TransitionRules.needsWireFlush(1));
        assertFalse(TransitionRules.needsWireFlush(0));
    }

    @Test
    @DisplayName("工艺窗口恰在上下边界视为合格，越界拒绝")
    void processWindowBoundariesInclusive() {
        BigDecimal min = new BigDecimal("830.000");
        BigDecimal max = new BigDecimal("1030.000");
        assertTrue(TransitionRules.withinWindow(min, min, max), "下边界合格");
        assertTrue(TransitionRules.withinWindow(max, min, max), "上边界合格");
        assertTrue(TransitionRules.withinWindow(new BigDecimal("900.000"), min, max));
        assertFalse(TransitionRules.withinWindow(new BigDecimal("829.999"), min, max));
        assertFalse(TransitionRules.withinWindow(new BigDecimal("1030.001"), min, max));
    }

    @Test
    @DisplayName("过渡料长度=车速×切换时间/60")
    void transitionLengthFormula() {
        // 900 m/min × 120 s / 60 = 1800 m
        assertEquals(0, new BigDecimal("1800.000").compareTo(
                TransitionRules.transitionLengthM(new BigDecimal("900.000"), 120)));
        // 830 m/min × 61 s / 60 = 843.833 m（HALF_UP）
        assertEquals(0, new BigDecimal("843.833").compareTo(
                TransitionRules.transitionLengthM(new BigDecimal("830.000"), 61)));
    }

    @Test
    @DisplayName("合成正品门槛恰达边界（灰分18、水分4/8、抗张45、克重偏差恰3%）视为达门槛")
    void qualityThresholdBoundariesInclusive() {
        BigDecimal nominal77 = new BigDecimal("77.00");
        // 77 × 1.03 = 79.31，恰 3% 偏差
        assertTrue(TransitionRules.meetsQualityThreshold(
                new BigDecimal("18.000"), new BigDecimal("4.000"),
                new BigDecimal("45.000"), new BigDecimal("79.31"), nominal77));
        assertTrue(TransitionRules.meetsQualityThreshold(
                new BigDecimal("17.000"), new BigDecimal("8.000"),
                new BigDecimal("46.000"), new BigDecimal("74.69"), nominal77));
        // 任一项越过边界即不达门槛
        assertFalse(TransitionRules.meetsQualityThreshold(
                new BigDecimal("18.001"), new BigDecimal("5.000"),
                new BigDecimal("45.000"), new BigDecimal("77.00"), nominal77));
        assertFalse(TransitionRules.meetsQualityThreshold(
                new BigDecimal("17.000"), new BigDecimal("3.999"),
                new BigDecimal("45.000"), new BigDecimal("77.00"), nominal77));
        assertFalse(TransitionRules.meetsQualityThreshold(
                new BigDecimal("17.000"), new BigDecimal("8.001"),
                new BigDecimal("45.000"), new BigDecimal("77.00"), nominal77));
        assertFalse(TransitionRules.meetsQualityThreshold(
                new BigDecimal("17.000"), new BigDecimal("5.000"),
                new BigDecimal("44.999"), new BigDecimal("77.00"), nominal77));
        assertFalse(TransitionRules.meetsQualityThreshold(
                new BigDecimal("17.000"), new BigDecimal("5.000"),
                new BigDecimal("45.000"), new BigDecimal("79.32"), nominal77));
    }

    @Test
    @DisplayName("掺配比例恰在合成允许区间边界（5%/25%）合法，越界拒绝")
    void blendRatioIntervalBoundariesInclusive() {
        assertTrue(TransitionRules.withinBlendInterval(new BigDecimal("5.000")), "恰下边界合法");
        assertTrue(TransitionRules.withinBlendInterval(new BigDecimal("25.000")), "恰上边界合法");
        assertTrue(TransitionRules.withinBlendInterval(new BigDecimal("15.000")));
        assertFalse(TransitionRules.withinBlendInterval(new BigDecimal("4.999")));
        assertFalse(TransitionRules.withinBlendInterval(new BigDecimal("25.001")));
    }

    @Test
    @DisplayName("掺配偏离恰 2% 合格，超过 2% 判超差")
    void blendDeviationBoundaryAtExactlyTwoPercent() {
        BigDecimal exactTwo = TransitionRules.blendDeviationPct(
                new BigDecimal("27.000"), new BigDecimal("25.000"));
        assertEquals(0, new BigDecimal("2.000").compareTo(exactTwo));
        assertFalse(TransitionRules.isBlendOutOfTolerance(exactTwo), "恰 2% 合格");

        BigDecimal beyond = TransitionRules.blendDeviationPct(
                new BigDecimal("27.001"), new BigDecimal("25.000"));
        assertEquals(0, new BigDecimal("2.001").compareTo(beyond));
        assertTrue(TransitionRules.isBlendOutOfTolerance(beyond), "超过 2% 判超差");

        // 负向偏离按绝对值计算
        BigDecimal negative = TransitionRules.blendDeviationPct(
                new BigDecimal("22.900"), new BigDecimal("25.000"));
        assertEquals(0, new BigDecimal("2.100").compareTo(negative));
        assertTrue(TransitionRules.isBlendOutOfTolerance(negative));
        assertFalse(TransitionRules.isBlendOutOfTolerance(
                TransitionRules.blendDeviationPct(new BigDecimal("25.000"), new BigDecimal("25.000"))));
    }

    @Test
    @DisplayName("应投回收量=总浆量×目标比例/100 精确计算：100kg×20%=20kg，2.5kg×20%=0.5kg")
    void requiredRecycleKgExactPercentMath() {
        assertEquals(0, new BigDecimal("20.00").compareTo(
                TransitionRules.requiredRecycleKg(new BigDecimal("100"), new BigDecimal("20.000"))));
        assertEquals(0, new BigDecimal("0.5").compareTo(
                TransitionRules.requiredRecycleKg(new BigDecimal("2.5"), new BigDecimal("20.000"))));
        // 超三位小数精度的组合原样暴露（0.001×20%=0.0002），由调用方拒绝
        BigDecimal overflow = TransitionRules.requiredRecycleKg(
                new BigDecimal("0.001"), new BigDecimal("20.000"));
        assertEquals(0, new BigDecimal("0.0002").compareTo(overflow));
        assertFalse(TransitionRules.withinWeightPrecision(overflow));
    }

    @Test
    @DisplayName("重量精度：最多三位小数（尾零不计），四位小数拒绝")
    void weightPrecisionAtMostThreeDecimals() {
        assertTrue(TransitionRules.withinWeightPrecision(new BigDecimal("10")));
        assertTrue(TransitionRules.withinWeightPrecision(new BigDecimal("10.5")));
        assertTrue(TransitionRules.withinWeightPrecision(new BigDecimal("10.500")));
        assertTrue(TransitionRules.withinWeightPrecision(new BigDecimal("0.001")));
        assertTrue(TransitionRules.withinWeightPrecision(new BigDecimal("10.5000")), "尾零不计入精度");
        assertFalse(TransitionRules.withinWeightPrecision(new BigDecimal("10.0001")));
        assertFalse(TransitionRules.withinWeightPrecision(new BigDecimal("0.0002")));
    }
}
