package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.domain.AgeBandRule;
import com.example.starter.cosmetic.domain.ChildBandDossier;
import com.example.starter.cosmetic.domain.ChildSafetyReportRecord;
import com.example.starter.cosmetic.domain.EfficacyCatalog;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.domain.RestrictedIngredient;
import com.example.starter.cosmetic.domain.TestReportRecord;
import com.example.starter.cosmetic.port.CatalogPort;
import com.example.starter.cosmetic.port.ReportPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 儿童分龄审查规则单元测试：年龄闭区间、儿童功效目录、分档材料齐备、
 * 报告覆盖与独立性、两档较严浓度限值（恰等合格）、缺项一次列全。
 */
class ChildFormReviewEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-06-30T08:00:00Z");
    private static final List<AgeBandRule> BANDS = List.of(
            new AgeBandRule("AGE-RULE-V1", "BAND_0_36", 0, 36),
            new AgeBandRule("AGE-RULE-V1", "BAND_37_144", 37, 144));

    private final ChildFormReviewEvaluator evaluator =
            new ChildFormReviewEvaluator(new StubCatalog(), new StubReports());

    private FormReviewEvaluator.Result evaluate(Integer lower, Integer upper,
                                                List<FormulationIngredient> formulation,
                                                List<String> claims,
                                                List<ChildBandDossier> dossiers) {
        return evaluator.evaluate("儿童测试品", formulation, claims, lower, upper, dossiers,
                BANDS, "AGE-RULE-V1", NOW, "SYN-F-000001", 1);
    }

    private static ChildBandDossier dossier(String band, String reportNo,
                                            int covLower, int covUpper, String label) {
        return new ChildBandDossier(band,
                new ChildSafetyReportRecord(reportNo, band, covLower, covUpper, true, "结论支持"),
                label);
    }

    private static List<FormulationIngredient> waterOnly() {
        return List.of(new FormulationIngredient("WATER", "水", new BigDecimal("80.0000")));
    }

    @Test
    void singleBandWithCompleteDossierPasses() {
        FormReviewEvaluator.Result result = evaluate(0, 36, waterOnly(), List.of("MOISTURIZING"),
                List.of(dossier("BAND_0_36", "CS-1", 0, 36, "适用于0-36个月婴幼儿")));

        assertThat(result.passed()).isTrue();
        assertThat(result.details()).extracting("ruleCode")
                .contains("DOC_COMPLETE", "CHILD_AGE_RANGE", "CHILD_CLAIM_CATALOG",
                        "CHILD_BAND_MATERIAL", "CHILD_REPORT_INDEPENDENT",
                        "CHILD_RESTRICTED_CONCENTRATION");
    }

    @Test
    void ageRangeViolationsAreRejected() {
        // 下限大于上限
        FormReviewEvaluator.Result inverted = evaluate(37, 36, waterOnly(),
                List.of("MOISTURIZING"), List.of());
        assertThat(rule(inverted, "CHILD_AGE_RANGE").passed()).isFalse();

        // 上限超过 144
        FormReviewEvaluator.Result overMax = evaluate(0, 145, waterOnly(),
                List.of("MOISTURIZING"), List.of());
        assertThat(rule(overMax, "CHILD_AGE_RANGE").passed()).isFalse();

        // 缺年龄
        FormReviewEvaluator.Result missing = evaluate(null, null, waterOnly(),
                List.of("MOISTURIZING"), List.of());
        assertThat(rule(missing, "CHILD_AGE_RANGE").passed()).isFalse();
    }

    @Test
    void crossBandRequiresBothBandDossiers() {
        // 跨档 0~144 只提供 0~36 档材料 → 缺 37~144 档
        FormReviewEvaluator.Result result = evaluate(0, 144, waterOnly(), List.of("MOISTURIZING"),
                List.of(dossier("BAND_0_36", "CS-1", 0, 36, "适用于0-36个月婴幼儿")));

        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "CHILD_BAND_MATERIAL").detail())
                .contains("BAND_37_144").contains("缺独立安全报告与适用年龄标识");
    }

    @Test
    void crossBandWithBothDossiersPasses() {
        FormReviewEvaluator.Result result = evaluate(0, 144, waterOnly(), List.of("MOISTURIZING"),
                List.of(dossier("BAND_0_36", "CS-1", 0, 36, "适用于0-36个月婴幼儿"),
                        dossier("BAND_37_144", "CS-2", 37, 144, "适用于37-144个月儿童")));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void reportMustExplicitlyCoverAppliedRangeInBand() {
        // 申请 12~36 月，报告只覆盖 0~24 月 → 未覆盖 25~36 段
        FormReviewEvaluator.Result result = evaluate(12, 36, waterOnly(), List.of("MOISTURIZING"),
                List.of(dossier("BAND_0_36", "CS-1", 0, 24, "适用于12-36个月婴幼儿")));

        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "CHILD_BAND_MATERIAL").detail()).contains("未明确覆盖申请年龄范围");
    }

    @Test
    void labelMustStateAppliedRangeWithinBand() {
        // 标识文本未明示申请年龄上下限（12、36）
        FormReviewEvaluator.Result result = evaluate(12, 36, waterOnly(), List.of("MOISTURIZING"),
                List.of(dossier("BAND_0_36", "CS-1", 0, 36, "婴幼儿适用")));

        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "CHILD_BAND_MATERIAL").detail()).contains("适用年龄标识未明示");
    }

    @Test
    void nonChildEfficacyClaimIsRejected() {
        FormReviewEvaluator.Result result = evaluate(0, 36, waterOnly(), List.of("SUNSCREEN"),
                List.of(dossier("BAND_0_36", "CS-1", 0, 36, "适用于0-36个月婴幼儿")));

        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "CHILD_CLAIM_CATALOG").detail()).contains("非儿童合成目录功效");
    }

    @Test
    void adultEfficacyReportCannotSubstituteChildSafetyReport() {
        // ADULT-R-1 已在成人功效评价试验报告库登记
        FormReviewEvaluator.Result result = evaluate(0, 36, waterOnly(), List.of("MOISTURIZING"),
                List.of(dossier("BAND_0_36", "ADULT-R-1", 0, 36, "适用于0-36个月婴幼儿")));

        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "CHILD_REPORT_INDEPENDENT").detail())
                .contains("成人功效报告不能替代儿童安全报告");
    }

    @Test
    void sameReportNumberAcrossBandsIsRejected() {
        FormReviewEvaluator.Result result = evaluate(0, 144, waterOnly(), List.of("MOISTURIZING"),
                List.of(dossier("BAND_0_36", "CS-DUP", 0, 36, "适用于0-36个月婴幼儿"),
                        dossier("BAND_37_144", "CS-DUP", 37, 144, "适用于37-144个月儿童")));

        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "CHILD_REPORT_INDEPENDENT").detail()).contains("各档须独立安全报告");
    }

    @Test
    void crossBandConcentrationUsesStricterLimitAndExactLimitPasses() {
        // 跨档：两档限值 0.5 / 1.0，较严者 0.5；恰等 0.5 合格
        FormReviewEvaluator.Result atLimit = evaluate(0, 144,
                List.of(new FormulationIngredient("SALICYLIC_ACID", "水杨酸", new BigDecimal("0.5000"))),
                List.of("MOISTURIZING"),
                List.of(dossier("BAND_0_36", "CS-1", 0, 36, "适用于0-36个月婴幼儿"),
                        dossier("BAND_37_144", "CS-2", 37, 144, "适用于37-144个月儿童")));
        assertThat(rule(atLimit, "CHILD_RESTRICTED_CONCENTRATION").passed()).isTrue();
        assertThat(rule(atLimit, "CHILD_RESTRICTED_CONCENTRATION").detail()).contains("恰等合法：通过");

        // 0.6 超过较严限值 0.5（虽未超 37~144 档 1.0 与成人 2.0）→ 拒绝
        FormReviewEvaluator.Result over = evaluate(0, 144,
                List.of(new FormulationIngredient("SALICYLIC_ACID", "水杨酸", new BigDecimal("0.6000"))),
                List.of("MOISTURIZING"),
                List.of(dossier("BAND_0_36", "CS-1", 0, 36, "适用于0-36个月婴幼儿"),
                        dossier("BAND_37_144", "CS-2", 37, 144, "适用于37-144个月儿童")));
        assertThat(rule(over, "CHILD_RESTRICTED_CONCENTRATION").passed()).isFalse();
    }

    @Test
    void singleBandConcentrationUsesThatBandsLimit() {
        // 单档 37~144：限值 1.0，恰等合格
        FormReviewEvaluator.Result atLimit = evaluate(37, 144,
                List.of(new FormulationIngredient("SALICYLIC_ACID", "水杨酸", new BigDecimal("1.0000"))),
                List.of("MOISTURIZING"),
                List.of(dossier("BAND_37_144", "CS-2", 37, 144, "适用于37-144个月儿童")));
        assertThat(rule(atLimit, "CHILD_RESTRICTED_CONCENTRATION").passed()).isTrue();

        FormReviewEvaluator.Result over = evaluate(37, 144,
                List.of(new FormulationIngredient("SALICYLIC_ACID", "水杨酸", new BigDecimal("1.0001"))),
                List.of("MOISTURIZING"),
                List.of(dossier("BAND_37_144", "CS-2", 37, 144, "适用于37-144个月儿童")));
        assertThat(rule(over, "CHILD_RESTRICTED_CONCENTRATION").passed()).isFalse();
    }

    @Test
    void allMissingItemsAreListedAtOnce() {
        // 同时违反：非儿童功效 + 跨档缺一档材料 + 年龄非法时不评档（此处年龄合法）
        FormReviewEvaluator.Result result = evaluate(0, 144, waterOnly(),
                List.of("SUNSCREEN", "UNKNOWN_CLAIM"), List.of());

        assertThat(result.passed()).isFalse();
        // 缺项一次列全：儿童目录与分档材料两条规则均不通过，且各规则明细列出全部问题
        assertThat(rule(result, "CHILD_CLAIM_CATALOG").passed()).isFalse();
        assertThat(rule(result, "CHILD_CLAIM_CATALOG").detail())
                .contains("SUNSCREEN").contains("UNKNOWN_CLAIM");
        assertThat(rule(result, "CHILD_BAND_MATERIAL").detail())
                .contains("BAND_0_36").contains("BAND_37_144");
        assertThat(result.reasons()).hasSizeGreaterThanOrEqualTo(2);
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
                new EfficacyCatalog("SUNSCREEN", "防晒", true, true, false));

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
        public List<AgeBandRule> listAgeBands(String ruleVersion) {
            return BANDS;
        }
    }

    /** 成人报告登记桩：ADULT-R-1 已登记为成人功效评价试验报告。 */
    private static final class StubReports implements ReportPort {

        @Override
        public void upsert(TestReportRecord report) {
        }

        @Override
        public Optional<TestReportRecord> findByNo(String reportNo) {
            if ("ADULT-R-1".equals(reportNo)) {
                return Optional.of(new TestReportRecord("ADULT-R-1", "SUNSCREEN",
                        LocalDate.of(2025, 1, 1), true, "成人防晒功效报告"));
            }
            return Optional.empty();
        }

        @Override
        public void upsertChildSafetyReport(ChildSafetyReportRecord report) {
        }

        @Override
        public Optional<ChildSafetyReportRecord> findChildSafetyReport(String reportNo) {
            return Optional.empty();
        }
    }
}
