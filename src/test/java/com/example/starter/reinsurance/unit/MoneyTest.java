package com.example.starter.reinsurance.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.starter.reinsurance.common.ApiException;
import com.example.starter.reinsurance.common.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 金额/比例/季度纯单元测试：比例计算、两位小数四舍五入、责任限额恰达边界。
 */
class MoneyTest {

    @Test
    void appliesCessionRateWithHalfUpRounding() {
        // 分出保费 1000 × 30% = 300
        assertEquals(new BigDecimal("300.00"),
                Money.applyRate(new BigDecimal("1000"), new BigDecimal("0.30")));
        // 105.555 × 30% = 31.6665 → HALF_UP 31.67
        assertEquals(new BigDecimal("31.67"),
                Money.applyRate(new BigDecimal("105.555"), new BigDecimal("0.30")));
        // 已决赔款 200 × 30% = 60
        assertEquals(new BigDecimal("60.00"),
                Money.applyRate(new BigDecimal("200"), new BigDecimal("0.30")));
    }

    @Test
    void capNeverExceedsRemainingCapacity() {
        // 摊回 120 大于剩余容量 100 → 按限额 100 计入，20 留在分出公司
        assertEquals(new BigDecimal("100.00"),
                Money.cap(new BigDecimal("120"), new BigDecimal("100")));
    }

    @Test
    void capExactlyAtLimitIsCountedAsLimit() {
        // 恰达限额：分摊金额恰为 100、容量恰为 100，按限额计入
        assertEquals(new BigDecimal("100.00"),
                Money.cap(new BigDecimal("100.00"), new BigDecimal("100.00")));
    }

    @Test
    void nullCapacityMeansUnlimited() {
        assertEquals(new BigDecimal("9999.99"),
                Money.cap(new BigDecimal("9999.99"), null));
    }

    @Test
    void rejectsOutOfRangeRate() {
        assertThrows(ApiException.class, () -> Money.validateRate(new BigDecimal("1.01")));
        assertThrows(ApiException.class, () -> Money.validateRate(new BigDecimal("-0.01")));
    }

    @Test
    void computesQuarterByUtcDate() {
        assertEquals("2026-Q1", Money.quarterOf(java.time.LocalDate.parse("2026-03-31")));
        assertEquals("2026-Q2", Money.quarterOf(java.time.LocalDate.parse("2026-04-01")));
        assertEquals("2026-Q3", Money.quarterOf(java.time.LocalDate.parse("2026-08-15")));
        assertEquals("2026-Q4", Money.quarterOf(java.time.LocalDate.parse("2026-12-31")));
    }
}
