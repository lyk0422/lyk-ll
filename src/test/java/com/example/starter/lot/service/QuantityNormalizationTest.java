package com.example.starter.lot.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数量精度与边界的纯单元测试（不依赖数据库）。
 */
class QuantityNormalizationTest {

    @Test
    @DisplayName("一至三位小数规范化为固定三位，等值输入不丢精度")
    void scalesToThreeDecimals() {
        assertThat(LotGenealogyService.normalizeQuantity(new BigDecimal("1"))).isEqualByComparingTo("1.000");
        assertThat(LotGenealogyService.normalizeQuantity(new BigDecimal("12.5"))).isEqualByComparingTo("12.500");
        assertThat(LotGenealogyService.normalizeQuantity(new BigDecimal("0.001"))).isEqualByComparingTo("0.001");
        // 尾随零等值表示应被接受，而不是误判为超精度。
        assertThat(LotGenealogyService.normalizeQuantity(new BigDecimal("1.0000"))).isEqualByComparingTo("1.000");
    }

    @Test
    @DisplayName("超过三位小数直接拒绝，不做静默四舍五入")
    void rejectsMoreThanThreeDecimals() {
        assertThatThrownBy(() -> LotGenealogyService.normalizeQuantity(new BigDecimal("1.0005")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("QTY_PRECISION_EXCEEDED");
        // 1.0004 若静默舍入会变成 1.000，这里必须拒绝。
        assertThatThrownBy(() -> LotGenealogyService.normalizeQuantity(new BigDecimal("1.0004")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("零与负数数量被拒绝")
    void rejectsZeroAndNegative() {
        assertThatThrownBy(() -> LotGenealogyService.normalizeQuantity(BigDecimal.ZERO))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("QTY_INVALID");
        assertThatThrownBy(() -> LotGenealogyService.normalizeQuantity(new BigDecimal("-0.001")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("允许损耗：零合法，负数拒绝，三位以上小数拒绝")
    void allowedLossRules() {
        assertThat(LotGenealogyService.normalizeNonNegative(BigDecimal.ZERO, "允许损耗"))
                .isEqualByComparingTo("0.000");
        assertThat(LotGenealogyService.normalizeNonNegative(new BigDecimal("2.5"), "允许损耗"))
                .isEqualByComparingTo("2.500");
        assertThatThrownBy(() -> LotGenealogyService.normalizeNonNegative(new BigDecimal("-0.001"), "允许损耗"))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> LotGenealogyService.normalizeNonNegative(new BigDecimal("0.0001"), "允许损耗"))
                .isInstanceOf(BusinessRuleException.class);
    }
}
