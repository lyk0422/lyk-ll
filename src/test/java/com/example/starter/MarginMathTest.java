package com.example.starter;

import com.example.starter.domain.MarginMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 金额与比例口径单元测试：130%/140%/110% 精确边界、强平金额向上取整到分、金额解析精度。
 */
class MarginMathTest {

    @Test
    void threshold130_exactBoundaryDoesNotTrigger() {
        // 恰好 130%：不触发追保
        assertFalse(MarginMath.below(13000, 10000, MarginMath.CALL_THRESHOLD_PCT));
        // 129.99%：触发
        assertTrue(MarginMath.below(12999, 10000, MarginMath.CALL_THRESHOLD_PCT));
    }

    @Test
    void threshold110_exactBoundaryStillMarginCall() {
        // 恰好 110%：不直接强平（仍走追保）
        assertFalse(MarginMath.below(11000, 10000, MarginMath.FORCE_THRESHOLD_PCT));
        // 109.99%：直接强平
        assertTrue(MarginMath.below(10999, 10000, MarginMath.FORCE_THRESHOLD_PCT));
    }

    @Test
    void threshold140_exactBoundaryCountsAsRecovered() {
        // 恰好 140%：视为恢复（含 140%）
        assertTrue(MarginMath.atLeast(14000, 10000, MarginMath.RECOVER_THRESHOLD_PCT));
        // 139.99%：未恢复
        assertFalse(MarginMath.atLeast(13999, 10000, MarginMath.RECOVER_THRESHOLD_PCT));
        // 无负债：视为达标
        assertTrue(MarginMath.atLeast(0, 0, MarginMath.RECOVER_THRESHOLD_PCT));
    }

    @Test
    void requiredRepayment_exactCent() {
        // A=129.00, L=100.00：需还 27.50 恰好回到 140%
        assertEquals(2750, MarginMath.requiredRepaymentCents(12900, 10000));
    }

    @Test
    void requiredRepayment_roundsUpToCent() {
        // A=129.01, L=100.00：(1400000-1290100)/40 = 2747.5，向上取整 2748 分
        long need = MarginMath.requiredRepaymentCents(12901, 10000);
        assertEquals(2748, need);
        // 还 2748 分后达标；少还 1 分则不达标
        assertTrue(MarginMath.atLeast(12901 - 2748, 10000 - 2748, MarginMath.RECOVER_THRESHOLD_PCT));
        assertFalse(MarginMath.atLeast(12901 - 2747, 10000 - 2747, MarginMath.RECOVER_THRESHOLD_PCT));
    }

    @Test
    void requiredRepayment_capsAtTotalLiabilities() {
        // 资产低于负债时全部平仓也无法恢复：平仓上限为全部负债
        assertEquals(10000, MarginMath.requiredRepaymentCents(9000, 10000));
        // 已达标无需平仓
        assertEquals(0, MarginMath.requiredRepaymentCents(14000, 10000));
        // 无负债无需平仓
        assertEquals(0, MarginMath.requiredRepaymentCents(100, 0));
    }

    @Test
    void parseCents_precisionAndFormat() {
        assertEquals(1, MarginMath.parseCents("0.01"));
        assertEquals(12900, MarginMath.parseCents("129"));
        assertEquals(12900, MarginMath.parseCents("129.00"));
        assertEquals("129.00", MarginMath.formatCents(12900));
        assertEquals("0.01", MarginMath.formatCents(1));
        // 超过分的精度拒绝，不静默四舍五入
        assertThrows(IllegalArgumentException.class, () -> MarginMath.parseCents("1.001"));
        assertThrows(IllegalArgumentException.class, () -> MarginMath.parseCents("-1.00"));
        assertThrows(IllegalArgumentException.class, () -> MarginMath.parseCents("abc"));
        assertThrows(IllegalArgumentException.class, () -> MarginMath.parseCents(" "));
    }

    @Test
    void ratioBp_halfUpAndNullForZeroLiability() {
        assertEquals(12999, MarginMath.ratioBp(12999, 10000));
        assertNull(MarginMath.ratioBp(100, 0));
        // 1/3 四舍五入到基点
        assertEquals(3333, MarginMath.ratioBp(10000, 30000));
    }
}
