package com.example.starter.torque.rule;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 力矩 ±5% 边界规则：恰在边界（0.95/1.05 倍标称值）合格，超差不合格。
 */
class TorqueToleranceTest {

    @Test
    void boundsAreExactlyFivePercent() {
        assertEquals(new BigDecimal("95.000"), TorqueTolerance.lowerBound(new BigDecimal("100")));
        assertEquals(new BigDecimal("105.000"), TorqueTolerance.upperBound(new BigDecimal("100")));
    }

    @Test
    void exactBoundariesPass() {
        BigDecimal nominal = new BigDecimal("100");
        assertTrue(TorqueTolerance.isWithin(new BigDecimal("95.000"), nominal), "恰 -5% 边界应合格");
        assertTrue(TorqueTolerance.isWithin(new BigDecimal("105.000"), nominal), "恰 +5% 边界应合格");
        assertTrue(TorqueTolerance.isWithin(new BigDecimal("100.000"), nominal), "标称值应合格");
    }

    @Test
    void outsideBoundariesFails() {
        BigDecimal nominal = new BigDecimal("100");
        assertFalse(TorqueTolerance.isWithin(new BigDecimal("94.999"), nominal), "低于 -5% 应超差");
        assertFalse(TorqueTolerance.isWithin(new BigDecimal("105.001"), nominal), "高于 +5% 应超差");
    }

    @Test
    void fractionalNominalBoundsUseDecimalArithmetic() {
        BigDecimal nominal = new BigDecimal("42.5");
        assertEquals(new BigDecimal("40.375"), TorqueTolerance.lowerBound(nominal));
        assertEquals(new BigDecimal("44.625"), TorqueTolerance.upperBound(nominal));
        assertTrue(TorqueTolerance.isWithin(new BigDecimal("40.375"), nominal));
        assertTrue(TorqueTolerance.isWithin(new BigDecimal("44.625"), nominal));
        assertFalse(TorqueTolerance.isWithin(new BigDecimal("40.374"), nominal));
    }
}
