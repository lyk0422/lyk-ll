package com.example.starter.margin.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阈值口径与金额精度纯单元测试：130%/140%/110% 的“恰好”边界与四舍五入。
 */
class RiskRulesTest {

    private boolean lessThan(String assets, String liabilities, String line) {
        return RiskRules.ratioLessThan(new BigDecimal(assets), new BigDecimal(liabilities),
                new BigDecimal(line));
    }

    private boolean atLeast(String assets, String liabilities, String line) {
        return RiskRules.ratioAtLeast(new BigDecimal(assets), new BigDecimal(liabilities),
                new BigDecimal(line));
    }

    @Test
    void ratioExactly130DoesNotTriggerMarginCall() {
        // 恰好 130% 不触发追保
        assertThat(lessThan("130.00", "100.00", "1.30")).isFalse();
        // 129.99% 触发
        assertThat(lessThan("129.99", "100.00", "1.30")).isTrue();
        // 分数尺度上的精确判定，不受比例四舍五入到 4 位影响：129.9999 与 130
        assertThat(lessThan("1299999", "1000000", "1.30")).isTrue();
    }

    @Test
    void ratioExactly110StillGoesMarginCallNotDirectLiquidation() {
        // 恰好 110% 不进入直接强平（仍走追保）
        assertThat(lessThan("110.00", "100.00", "1.10")).isFalse();
        // 109.99% 直接强平
        assertThat(lessThan("109.99", "100.00", "1.10")).isTrue();
    }

    @Test
    void maintenanceLineIsInclusiveAt140() {
        // 补足到恰好 140% 视为达标（含 140%）
        assertThat(atLeast("140.00", "100.00", "1.40")).isTrue();
        assertThat(atLeast("139.99", "100.00", "1.40")).isFalse();
    }

    @Test
    void moneyRoundsHalfUpToCent() {
        assertThat(RiskRules.money(new BigDecimal("1.005"))).isEqualByComparingTo("1.01");
        assertThat(RiskRules.money(new BigDecimal("1.004"))).isEqualByComparingTo("1.00");
        assertThat(RiskRules.money(new BigDecimal("2.675"))).isEqualByComparingTo("2.68");
    }

    @Test
    void ratioRoundsTo4DecimalsAndNullWhenNoDebt() {
        assertThat(RiskRules.ratio(new BigDecimal("31.50"), new BigDecimal("22.50")))
                .isEqualByComparingTo("1.4000");
        assertThat(RiskRules.ratio(new BigDecimal("10.00"), BigDecimal.ZERO)).isNull();
    }
}
