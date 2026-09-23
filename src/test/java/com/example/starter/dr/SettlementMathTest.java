package com.example.starter.dr;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 单区间结算纯函数测试：max(0,基线-实测) 且以承诺值封顶；缺值为 PENDING（null）。
 */
class SettlementMathTest {

    @Test
    void deliveredIsReductionWhenBelowCommitment() {
        assertEquals(new BigDecimal("30"),
                SettlementMath.delivered(new BigDecimal("100"), new BigDecimal("70"), new BigDecimal("40")));
    }

    @Test
    void deliveredIsCappedByCommittedPower() {
        assertEquals(new BigDecimal("40"),
                SettlementMath.delivered(new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("40")));
    }

    @Test
    void negativeReductionSettlesAtZero() {
        assertEquals(new BigDecimal("0"),
                SettlementMath.delivered(new BigDecimal("60"), new BigDecimal("80"), new BigDecimal("40")));
    }

    @Test
    void missingBaselineOrActualIsPendingNotZero() {
        assertNull(SettlementMath.delivered(null, new BigDecimal("70"), new BigDecimal("40")));
        assertNull(SettlementMath.delivered(new BigDecimal("100"), null, new BigDecimal("40")));
    }
}
