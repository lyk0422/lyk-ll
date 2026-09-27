package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.domain.EfficacyCatalog;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.domain.RestrictedIngredient;
import com.example.starter.cosmetic.domain.TestReportRecord;
import com.example.starter.cosmetic.port.CatalogPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 形式审查规则单元测试：3 年恰满当日、超期一天、宣称一致性、限用浓度恰等/超限、
 * 不支持的功效宣称拒绝、特殊功效缺报告拒绝。
 */
class FormReviewEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-06-30T08:00:00Z");
    private static final LocalDate SUBMIT_DATE = LocalDate.of(2026, 6, 30);

    private final FormReviewEvaluator evaluator = new FormReviewEvaluator(new StubCatalog());

    private FormReviewEvaluator.Result evaluate(List<FormulationIngredient> formulation,
                                                List<String> claims,
                                                List<TestReportRecord> reports) {
        return evaluator.evaluate("测试产品", formulation, claims, reports,
                SUBMIT_DATE, NOW, "SYN-F-000001", 1);
    }

    private TestReportRecord sunscreenReport(LocalDate issued, boolean supported) {
        return new TestReportRecord("R-1", "SUNSCREEN", issued, supported, "功效评价结论");
    }

    @Test
    void reportExactlyThreeYearsOldIsValid() {
        // 出具日恰为提交日前 3 年当日（UTC 日界），有效
        FormReviewEvaluator.Result result = evaluate(
                List.of(new FormulationIngredient("WATER", "水", new BigDecimal("80.00"))),
                List.of("SUNSCREEN"),
                List.of(sunscreenReport(LocalDate.of(2023, 6, 30), true)));

        assertThat(result.passed()).isTrue();
        assertThat(rule(result, "REPORT_VALIDITY").passed()).isTrue();
        assertThat(rule(result, "REPORT_VALIDITY").detail()).contains("恰满3年当日有效");
    }

    @Test
    void reportOneDayBeyondThreeYearsIsRejected() {
        FormReviewEvaluator.Result result = evaluate(
                List.of(new FormulationIngredient("WATER", "水", new BigDecimal("80.00"))),
                List.of("SUNSCREEN"),
                List.of(sunscreenReport(LocalDate.of(2023, 6, 29), true)));

        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "REPORT_VALIDITY").passed()).isFalse();
        assertThat(result.reasons()).anyMatch(s -> s.contains("REPORT_VALIDITY"));
    }

    @Test
    void claimReportConclusionMismatchIsRejected() {
        // 报告在有效期内但结论不支持宣称
        FormReviewEvaluator.Result result = evaluate(
                List.of(new FormulationIngredient("WATER", "水", new BigDecimal("80.00"))),
                List.of("SUNSCREEN"),
                List.of(sunscreenReport(LocalDate.of(2025, 1, 1), false)));

        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "CLAIM_REPORT_CONSISTENCY").passed()).isFalse();
    }

    @Test
    void restrictedConcentrationExactlyAtLimitIsLegal() {
        FormReviewEvaluator.Result result = evaluate(
                List.of(new FormulationIngredient("SALICYLIC_ACID", "水杨酸", new BigDecimal("2.0000"))),
                List.of("MOISTURIZING"), List.of());

        assertThat(result.passed()).isTrue();
        assertThat(rule(result, "RESTRICTED_CONCENTRATION").detail()).contains("恰等合法：通过");
    }

    @Test
    void restrictedConcentrationOverLimitIsRejected() {
        FormReviewEvaluator.Result result = evaluate(
                List.of(new FormulationIngredient("SALICYLIC_ACID", "水杨酸", new BigDecimal("2.0001"))),
                List.of("MOISTURIZING"), List.of());

        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "RESTRICTED_CONCENTRATION").passed()).isFalse();
    }

    @Test
    void unsupportedEfficacyClaimIsRejected() {
        FormReviewEvaluator.Result result = evaluate(
                List.of(new FormulationIngredient("WATER", "水", new BigDecimal("80"))),
                List.of("MEDICAL_CURE"), List.of());

        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "CLAIM_CATALOG").passed()).isFalse();
        assertThat(rule(result, "CLAIM_CATALOG").detail()).contains("不支持的功效宣称");
    }

    @Test
    void specialClaimWithoutReportIsRejected() {
        FormReviewEvaluator.Result result = evaluate(
                List.of(new FormulationIngredient("WATER", "水", new BigDecimal("80"))),
                List.of("WHITENING"), List.of());

        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "SPECIAL_REPORT_PRESENT").passed()).isFalse();
    }

    @Test
    void ordinaryCosmeticWithCompleteDocumentsPassesWithoutReports() {
        FormReviewEvaluator.Result result = evaluate(
                List.of(new FormulationIngredient("WATER", "水", new BigDecimal("80"))),
                List.of("MOISTURIZING"), List.of());

        assertThat(result.passed()).isTrue();
        assertThat(result.details()).hasSize(6);
    }

    private static com.example.starter.cosmetic.domain.ReviewDetail rule(
            FormReviewEvaluator.Result result, String code) {
        return result.details().stream()
                .filter(d -> d.ruleCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少规则明细: " + code));
    }

    /** 合成目录桩。 */
    private static final class StubCatalog implements CatalogPort {

        private static final List<EfficacyCatalog> EFFICACIES = List.of(
                new EfficacyCatalog("MOISTURIZING", "保湿", false, true, true),
                new EfficacyCatalog("SUNSCREEN", "防晒", true, true, false),
                new EfficacyCatalog("WHITENING", "美白", true, true, false),
                new EfficacyCatalog("MEDICAL_CURE", "医疗治疗作用", true, false, false));

        private static final List<RestrictedIngredient> RESTRICTED = List.of(
                new RestrictedIngredient("SALICYLIC_ACID", "水杨酸", new BigDecimal("2.0000"),
                        new BigDecimal("0.5000"), new BigDecimal("1.0000")));

        @Override
        public Optional<EfficacyCatalog> findEfficacy(String code) {
            return EFFICACIES.stream().filter(e -> e.code().equals(code)).findFirst();
        }

        @Override
        public List<EfficacyCatalog> listEfficacies() {
            return EFFICACIES;
        }

        @Override
        public Optional<RestrictedIngredient> findRestricted(String ingredientCode) {
            return RESTRICTED.stream().filter(r -> r.ingredientCode().equals(ingredientCode)).findFirst();
        }

        @Override
        public String currentAgeRuleVersion() {
            return "AGE-RULE-V1";
        }

        @Override
        public List<com.example.starter.cosmetic.domain.AgeBandRule> listAgeBands(String ruleVersion) {
            return List.of(
                    new com.example.starter.cosmetic.domain.AgeBandRule(ruleVersion, "BAND_0_36", 0, 36),
                    new com.example.starter.cosmetic.domain.AgeBandRule(ruleVersion, "BAND_37_144", 37, 144));
        }
    }
}
