package com.example.starter.transition.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收投料纯规则：三位小数精度判定与应投回收量精确计算。
 */
class RecycleRulesTest {

    @Test
    @DisplayName("三位小数判定：末尾零不计较，第四位非零越界")
    void withinThreeDecimals() {
        assertTrue(TransitionRules.withinThreeDecimals(new BigDecimal("10")));
        assertTrue(TransitionRules.withinThreeDecimals(new BigDecimal("10.000")));
        assertTrue(TransitionRules.withinThreeDecimals(new BigDecimal("0.001")));
        assertTrue(TransitionRules.withinThreeDecimals(new BigDecimal("20.500")));
        assertFalse(TransitionRules.withinThreeDecimals(new BigDecimal("10.0005")));
        assertFalse(TransitionRules.withinThreeDecimals(new BigDecimal("0.0001")));
    }

    @Test
    @DisplayName("应投回收量：100kg×20% 精确等于 20kg，可精确到三位小数")
    void requiredRecycleKgExact() {
        Optional<BigDecimal> required = TransitionRules.requiredRecycleKg(
                new BigDecimal("100"), new BigDecimal("20.000"));
        assertTrue(required.isPresent());
        assertEquals(0, required.get().compareTo(new BigDecimal("20.00")),
                "100kg×20% 应精确等于 20kg");
    }

    @Test
    @DisplayName("应投回收量：计算结果超过三位小数精度时拒绝该组合")
    void requiredRecycleKgPrecisionExceeded() {
        // 33.333 × 20% = 6.6666，超过三位小数。
        assertTrue(TransitionRules.requiredRecycleKg(
                new BigDecimal("33.333"), new BigDecimal("20.000")).isEmpty());
        // 10.001 × 5.5% = 0.550055，超过三位小数。
        assertTrue(TransitionRules.requiredRecycleKg(
                new BigDecimal("10.001"), new BigDecimal("5.500")).isEmpty());
        // 33.333 × 3% = 0.99999 → 越界；33.333 × 30% = 9.9999 → 越界。
        assertTrue(TransitionRules.requiredRecycleKg(
                new BigDecimal("33.333"), new BigDecimal("30.000")).isEmpty());
    }

    @Test
    @DisplayName("应投回收量：边界可精确组合不拒绝")
    void requiredRecycleKgBoundaryExact() {
        // 33.333 × 21% = 6.99993 → 越界；33.333 × 24% = 7.99992 → 越界；33.333 × 3.3% = 1.0999889 → 越界。
        assertTrue(TransitionRules.requiredRecycleKg(
                new BigDecimal("33.333"), new BigDecimal("21.000")).isEmpty());
        // 0.125 × 20% = 0.025，三位小数内合法。
        Optional<BigDecimal> ok = TransitionRules.requiredRecycleKg(
                new BigDecimal("0.125"), new BigDecimal("20.000"));
        assertTrue(ok.isPresent());
        assertEquals(0, ok.get().compareTo(new BigDecimal("0.025")));
    }
}
