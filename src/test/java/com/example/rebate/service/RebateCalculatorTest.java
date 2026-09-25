package com.example.rebate.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 计税依据、换汇成本区间与到账差异阈值的纯函数测试（不依赖数据库）。
 */
class RebateCalculatorTest {

    @Test
    void taxBasisIsFobTimesRate() {
        assertEquals(new BigDecimal("65.0000"),
                RebateCalculator.taxBasis(new BigDecimal("500.00"), new BigDecimal("0.13")));
    }

    @Test
    void exchangeCostExactlyLowerBoundIsLegal() {
        // 计税依据 50.0000，FOB 美元 10 -> 换汇成本恰 5.000000
        BigDecimal basis = RebateCalculator.taxBasis(new BigDecimal("500"), new BigDecimal("0.10"));
        BigDecimal cost = RebateCalculator.exchangeCost(basis, new BigDecimal("10"));
        assertEquals(0, new BigDecimal("5.000000").compareTo(cost));
        assertTrue(RebateCalculator.exchangeCostInRange(cost));
    }

    @Test
    void exchangeCostExactlyUpperBoundIsLegal() {
        // 计税依据 80.0000，FOB 美元 10 -> 换汇成本恰 8.000000
        BigDecimal basis = RebateCalculator.taxBasis(new BigDecimal("800"), new BigDecimal("0.10"));
        BigDecimal cost = RebateCalculator.exchangeCost(basis, new BigDecimal("10"));
        assertEquals(0, new BigDecimal("8.000000").compareTo(cost));
        assertTrue(RebateCalculator.exchangeCostInRange(cost));
    }

    @Test
    void exchangeCostJustOutsideBoundsIsIllegal() {
        BigDecimal lowBasis = RebateCalculator.taxBasis(new BigDecimal("499"), new BigDecimal("0.10"));
        BigDecimal lowCost = RebateCalculator.exchangeCost(lowBasis, new BigDecimal("10"));
        assertFalse(RebateCalculator.exchangeCostInRange(lowCost));

        BigDecimal highBasis = RebateCalculator.taxBasis(new BigDecimal("801"), new BigDecimal("0.10"));
        BigDecimal highCost = RebateCalculator.exchangeCost(highBasis, new BigDecimal("10"));
        assertFalse(RebateCalculator.exchangeCostInRange(highCost));
    }

    @Test
    void diffExactlyHalfPercentIsConsistent() {
        // 申报 1000，到账 995 -> 差额恰 0.5%
        BigDecimal ratio = RebateCalculator.diffRatio(new BigDecimal("1000"), new BigDecimal("995"));
        assertEquals(0, new BigDecimal("0.00500000").compareTo(ratio));
        assertTrue(RebateCalculator.diffConsistent(ratio));
    }

    @Test
    void diffJustOverHalfPercentGoesManual() {
        // 申报 1000，到账 994.99 -> 0.501% > 0.5%
        BigDecimal ratio = RebateCalculator.diffRatio(new BigDecimal("1000"), new BigDecimal("994.99"));
        assertEquals(0, new BigDecimal("0.00501000").compareTo(ratio));
        assertFalse(RebateCalculator.diffConsistent(ratio));
    }
}
