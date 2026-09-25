package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.domain.SeedGrade;

/**
 * 门槛判定、不得越级分级与 15 个自然日复检窗口的纯规则边界测试。
 */
class CertificationRulesTest {

    @Test
    @DisplayName("田间纯度：原种/良种恰好达标判合格，低 0.001 判不合格")
    void purityBoundary() {
        assertThat(CertificationRules.purityPasses(SeedGrade.ORIGINAL, new BigDecimal("99.900")))
                .isTrue();
        assertThat(CertificationRules.purityPasses(SeedGrade.ORIGINAL, new BigDecimal("99.9")))
                .isTrue();
        assertThat(CertificationRules.purityPasses(SeedGrade.ORIGINAL, new BigDecimal("99.899")))
                .isFalse();
        assertThat(CertificationRules.purityPasses(SeedGrade.IMPROVED, new BigDecimal("99.000")))
                .isTrue();
        assertThat(CertificationRules.purityPasses(SeedGrade.IMPROVED, new BigDecimal("98.999")))
                .isFalse();
    }

    @Test
    @DisplayName("发芽率：原种 90%、良种 85% 恰好达标判合格，低于门槛判不合格")
    void germinationBoundary() {
        assertThat(CertificationRules.germinationPasses(SeedGrade.ORIGINAL,
                new BigDecimal("90.000"))).isTrue();
        assertThat(CertificationRules.germinationPasses(SeedGrade.ORIGINAL,
                new BigDecimal("89.999"))).isFalse();
        assertThat(CertificationRules.germinationPasses(SeedGrade.IMPROVED,
                new BigDecimal("85.000"))).isTrue();
        assertThat(CertificationRules.germinationPasses(SeedGrade.IMPROVED,
                new BigDecimal("84.999"))).isFalse();
    }

    @Test
    @DisplayName("不得越级：双项达原种发原种；仅达良种发良种；一项不达良种不发证")
    void gradingCannotSkipLevel() {
        assertThat(CertificationRules.gradeFor(new BigDecimal("99.900"),
                new BigDecimal("90.000"))).isEqualTo(SeedGrade.ORIGINAL);
        assertThat(CertificationRules.gradeFor(new BigDecimal("100.000"),
                new BigDecimal("99.999"))).isEqualTo(SeedGrade.ORIGINAL);
        // 纯度达原种、发芽率仅达良种 → 只发良种证，不得越级
        assertThat(CertificationRules.gradeFor(new BigDecimal("99.900"),
                new BigDecimal("85.000"))).isEqualTo(SeedGrade.IMPROVED);
        // 双项仅达良种门槛 → 良种
        assertThat(CertificationRules.gradeFor(new BigDecimal("99.000"),
                new BigDecimal("85.000"))).isEqualTo(SeedGrade.IMPROVED);
        // 纯度低于良种门槛 → 不得发证
        assertThat(CertificationRules.gradeFor(new BigDecimal("98.999"),
                new BigDecimal("95.000"))).isNull();
        // 发芽率低于良种门槛 → 不得发证
        assertThat(CertificationRules.gradeFor(new BigDecimal("99.500"),
                new BigDecimal("84.999"))).isNull();
    }

    @Test
    @DisplayName("复检窗口：首检后第 15 日（含）受理，第 16 日逾期拒绝")
    void retestWindowNaturalDaysUtc() {
        Instant first = Instant.parse("2026-09-01T10:15:30Z");

        // 同日、第 1 日受理
        assertThat(CertificationRules.withinRetestWindow(first,
                Instant.parse("2026-09-01T23:59:59Z"))).isTrue();
        assertThat(CertificationRules.withinRetestWindow(first,
                Instant.parse("2026-09-02T00:00:00Z"))).isTrue();

        // 恰在第 15 日（2026-09-16），当天最后一刻仍受理
        Instant day15End = Instant.parse("2026-09-16T23:59:59Z");
        assertThat(CertificationRules.retestDeadline(first))
                .isEqualTo(java.time.LocalDate.parse("2026-09-16"));
        assertThat(CertificationRules.withinRetestWindow(first, day15End)).isTrue();

        // 跨过 UTC 日界即逾期
        assertThat(CertificationRules.withinRetestWindow(first,
                Instant.parse("2026-09-17T00:00:00Z"))).isFalse();
    }
}
