package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.domain.AgeLabel;
import com.example.starter.cosmetic.domain.ChildrenAgeBandRule;
import com.example.starter.cosmetic.domain.ChildrenBandLimit;
import com.example.starter.cosmetic.domain.ChildrenEfficacy;
import com.example.starter.cosmetic.domain.ChildrenSafetyReport;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.domain.ReviewDetail;
import com.example.starter.cosmetic.port.ChildrenCatalogPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 儿童分龄资料审查器纯单元测试（内存目录桩，可复算）。
 */
class ChildrenReviewEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-06-30T08:00:00Z");

    private ChildrenReviewEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new ChildrenReviewEvaluator(new StubCatalogPort());
    }

    /** 内存合成目录桩：与 data.sql 合成初始化口径一致。 */
    private static final class StubCatalogPort implements ChildrenCatalogPort {

        private final List<ChildrenAgeBandRule> bands = List.of(
                new ChildrenAgeBandRule("INFANT", 0, 36, 1),
                new ChildrenAgeBandRule("CHILD", 37, 144, 1));

        private final Map<String, ChildrenBandLimit> limits = new HashMap<>();

        private final Map<String, ChildrenEfficacy> efficacies = Map.of(
                "MOISTURIZING", new ChildrenEfficacy("MOISTURIZING", "保湿", true),
                "SUNSCREEN", new ChildrenEfficacy("SUNSCREEN", "防晒", true),
                "WHITENING", new ChildrenEfficacy("WHITENING", "美白", false));

        StubCatalogPort() {
            limits.put("INFANT|PHENOXYETHANOL",
                    new ChildrenBandLimit("INFANT", "PHENOXYETHANOL", new BigDecimal("0.4000")));
            limits.put("CHILD|PHENOXYETHANOL",
                    new ChildrenBandLimit("CHILD", "PHENOXYETHANOL", new BigDecimal("1.0000")));
            limits.put("INFANT|SALICYLIC_ACID",
                    new ChildrenBandLimit("INFANT", "SALICYLIC_ACID", new BigDecimal("0.5000")));
            limits.put("CHILD|SALICYLIC_ACID",
                    new ChildrenBandLimit("CHILD", "SALICYLIC_ACID", new BigDecimal("2.0000")));
        }

        @Override
        public List<ChildrenAgeBandRule> listBandRules() {
            return bands;
        }

        @Override
        public Optional<ChildrenAgeBandRule> findBandRule(String bandCode) {
            return bands.stream().filter(b -> b.bandCode().equals(bandCode)).findFirst();
        }

        @Override
        public int currentRuleVersion() {
            return 1;
        }

        @Override
        public Optional<ChildrenBandLimit> findBandLimit(String bandCode, String ingredientCode) {
            return Optional.ofNullable(limits.get(bandCode + "|" + ingredientCode));
        }

        @Override
        public Optional<ChildrenEfficacy> findChildrenEfficacy(String code) {
            return Optional.ofNullable(efficacies.get(code));
        }
    }

    private static List<FormulationIngredient> gentle() {
        return List.of(new FormulationIngredient("WATER", "水", new BigDecimal("80.0000")));
    }

    private static ChildrenSafetyReport report(String no, String band, int lower, int upper, boolean ok) {
        return new ChildrenSafetyReport(no, band, lower, upper, LocalDate.of(2026, 1, 15), ok, "合成");
    }

    private static AgeLabel label(String band, int lower, int upper) {
        return new AgeLabel(band, "适用标识", lower, upper);
    }

    private ChildrenReviewEvaluator.Result evaluate(int lower, int upper,
                                                    List<FormulationIngredient> formulation,
                                                    List<String> claims,
                                                    List<ChildrenSafetyReport> reports,
                                                    List<AgeLabel> labels,
                                                    int adultReportCount) {
        return evaluator.evaluate("合成儿童霜", lower, upper, formulation, claims,
                reports, labels, adultReportCount, NOW, "SYN-C-000001", 1);
    }

    @Test
    void crossBandWithBothBandsMaterialsPasses() {
        ChildrenReviewEvaluator.Result result = evaluate(0, 144, gentle(),
                List.of("MOISTURIZING"),
                List.of(report("SR-1", "INFANT", 0, 36, true), report("SR-2", "CHILD", 37, 144, true)),
                List.of(label("INFANT", 0, 36), label("CHILD", 37, 144)), 0);

        assertThat(result.passed()).isTrue();
        assertThat(result.reasons()).isEmpty();
        assertThat(result.details()).extracting(ReviewDetail::ruleCode)
                .contains("AGE_RANGE_VALID", "BAND_SAFETY_REPORT", "SAFETY_REPORT_COVERAGE",
                        "BAND_AGE_LABEL", "AGE_LABEL_COVERAGE", "CHILD_CONCENTRATION");
    }

    @Test
    void crossBandMissingOneBandReportAndLabelListsAllProblemsAtOnce() {
        ChildrenReviewEvaluator.Result result = evaluate(0, 144, gentle(),
                List.of("MOISTURIZING"),
                List.of(report("SR-1", "INFANT", 0, 36, true)),
                List.of(label("INFANT", 0, 36)), 0);

        assertThat(result.passed()).isFalse();
        // 跨档须两档资料都齐：CHILD 档报告与标识缺项一次列全
        assertThat(result.reasons()).anyMatch(r -> r.contains("BAND_SAFETY_REPORT") && r.contains("CHILD"));
        assertThat(result.reasons()).anyMatch(r -> r.contains("BAND_AGE_LABEL") && r.contains("CHILD"));
        assertThat(result.details()).filteredOn(d -> !d.passed()).hasSize(2);
    }

    @Test
    void adultReportsCannotSubstituteChildrenSafetyReport() {
        ChildrenReviewEvaluator.Result result = evaluate(0, 36, gentle(),
                List.of("MOISTURIZING"), List.of(), List.of(label("INFANT", 0, 36)), 2);

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("BAND_SAFETY_REPORT")
                && r.contains("不能替代儿童安全报告"));
    }

    @Test
    void safetyReportMustExplicitlyCoverAppliedAgeRange() {
        // 报告只覆盖 0~12 月，申请 0~36 月：未明确覆盖申请年龄范围
        ChildrenReviewEvaluator.Result result = evaluate(0, 36, gentle(),
                List.of("MOISTURIZING"),
                List.of(report("SR-1", "INFANT", 0, 12, true)),
                List.of(label("INFANT", 0, 36)), 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("SAFETY_REPORT_COVERAGE"));
    }

    @Test
    void unsupportedOrUnknownChildrenEfficacyRejected() {
        ChildrenReviewEvaluator.Result unsupported = evaluate(0, 36, gentle(),
                List.of("WHITENING"),
                List.of(report("SR-1", "INFANT", 0, 36, true)),
                List.of(label("INFANT", 0, 36)), 0);
        assertThat(unsupported.passed()).isFalse();
        assertThat(unsupported.reasons()).anyMatch(r -> r.contains("儿童不允许的功效宣称"));

        ChildrenReviewEvaluator.Result unknown = evaluate(0, 36, gentle(),
                List.of("NOT_A_CLAIM"),
                List.of(report("SR-1", "INFANT", 0, 36, true)),
                List.of(label("INFANT", 0, 36)), 0);
        assertThat(unknown.passed()).isFalse();
        assertThat(unknown.reasons()).anyMatch(r -> r.contains("不在儿童合成目录"));
    }

    @Test
    void concentrationJudgedByStricterOfTwoBandsAndExactLimitPasses() {
        // 跨档：苯氧乙醇两档限值 0.4/1.0，较严者 0.4；恰等 0.4 合格
        List<FormulationIngredient> atStrictest = List.of(
                new FormulationIngredient("PHENOXYETHANOL", "苯氧乙醇", new BigDecimal("0.4000")));
        ChildrenReviewEvaluator.Result atLimit = evaluate(0, 144, atStrictest,
                List.of("MOISTURIZING"),
                List.of(report("SR-1", "INFANT", 0, 36, true), report("SR-2", "CHILD", 37, 144, true)),
                List.of(label("INFANT", 0, 36), label("CHILD", 37, 144)), 0);
        assertThat(atLimit.passed()).isTrue();
        assertThat(atLimit.details()).anyMatch(d -> d.ruleCode().equals("CHILD_CONCENTRATION")
                && d.passed() && d.detail().contains("0.4"));

        // 0.6 超过较严档 0.4（即使在较宽档 1.0 内）→ 不合格
        List<FormulationIngredient> overStrictest = List.of(
                new FormulationIngredient("PHENOXYETHANOL", "苯氧乙醇", new BigDecimal("0.6000")));
        ChildrenReviewEvaluator.Result over = evaluate(0, 144, overStrictest,
                List.of("MOISTURIZING"),
                List.of(report("SR-1", "INFANT", 0, 36, true), report("SR-2", "CHILD", 37, 144, true)),
                List.of(label("INFANT", 0, 36), label("CHILD", 37, 144)), 0);
        assertThat(over.passed()).isFalse();
        assertThat(over.reasons()).anyMatch(r -> r.contains("CHILD_CONCENTRATION") && r.contains("超限"));
    }

    @Test
    void singleBandUsesThatBandsLimit() {
        // 单档 37~144：苯氧乙醇限值 1.0，0.6 合格
        List<FormulationIngredient> formulation = List.of(
                new FormulationIngredient("PHENOXYETHANOL", "苯氧乙醇", new BigDecimal("0.6000")));
        ChildrenReviewEvaluator.Result result = evaluate(37, 144, formulation,
                List.of("MOISTURIZING"),
                List.of(report("SR-2", "CHILD", 37, 144, true)),
                List.of(label("CHILD", 37, 144)), 0);
        assertThat(result.passed()).isTrue();
    }

    @Test
    void labelRangeMustCoverAppliedAgeRange() {
        ChildrenReviewEvaluator.Result result = evaluate(0, 36, gentle(),
                List.of("MOISTURIZING"),
                List.of(report("SR-1", "INFANT", 0, 36, true)),
                List.of(label("INFANT", 12, 36)), 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("AGE_LABEL_COVERAGE"));
    }

    @Test
    void invalidAgeRangeRejected() {
        ChildrenReviewEvaluator.Result result = evaluate(40, 36, gentle(),
                List.of("MOISTURIZING"), List.of(), List.of(), 0);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("AGE_RANGE_VALID"));
    }

    @Test
    void unsupportedConclusionReportRejected() {
        ChildrenReviewEvaluator.Result result = evaluate(0, 36, gentle(),
                List.of("MOISTURIZING"),
                List.of(report("SR-1", "INFANT", 0, 36, false)),
                List.of(label("INFANT", 0, 36)), 0);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("SAFETY_REPORT_CONCLUSION"));
    }
}
