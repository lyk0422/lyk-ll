package com.example.starter.cosmetic.child.service;

import com.example.starter.cosmetic.child.domain.AgeTier;
import com.example.starter.cosmetic.child.domain.ChildEfficacy;
import com.example.starter.cosmetic.child.domain.ChildIngredientLimit;
import com.example.starter.cosmetic.child.domain.ChildReviewDetail;
import com.example.starter.cosmetic.child.domain.ChildSafetyReport;
import com.example.starter.cosmetic.child.port.ChildCatalogPort;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 儿童分龄形式审查规则单元测试：单档/跨档命中、各档独立报告与覆盖范围、
 * 跨档较严限值恰等合格/超限、成人功效不可替代、标识范围、结论一致性，
 * 且任一缺项一次列全。
 */
class ChildAgeReviewEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-06-30T08:00:00Z");
    private static final String RULE = "CHILD-AGE-RULE-V1";

    private final ChildAgeReviewEvaluator evaluator = new ChildAgeReviewEvaluator(new StubCatalog());

    private ChildSafetyReport report(AgeTier tier, int from, int to, boolean supported) {
        return new ChildSafetyReport("R-" + tier + "-" + from + "-" + to, tier,
                LocalDate.of(2026, 1, 1), from, to, supported, "儿童安全评价结论");
    }

    private ChildAgeReviewEvaluator.Result evaluate(int from, int to,
                                                    List<FormulationIngredient> formulation,
                                                    List<String> claims,
                                                    List<ChildSafetyReport> reports,
                                                    String label) {
        return evaluator.evaluate("儿童测试产品", from, to, formulation, claims, reports, label,
                RULE, NOW, "SYN-CF-000001", 1);
    }

    private static List<FormulationIngredient> water() {
        return List.of(new FormulationIngredient("WATER", "水", new BigDecimal("80.0000")));
    }

    private static ChildReviewDetail rule(ChildAgeReviewEvaluator.Result r, String code) {
        return r.details().stream().filter(d -> d.ruleCode().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少规则明细: " + code));
    }

    /** 跨档时同一规则码每档各一条，按明细中的分档编码选择。 */
    private static ChildReviewDetail ruleForTier(ChildAgeReviewEvaluator.Result r, String code, AgeTier tier) {
        return r.details().stream()
                .filter(d -> d.ruleCode().equals(code) && d.detail().contains(tier.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少分档 " + tier + " 规则明细: " + code));
    }

    @Test
    void singleTierYoungWithCompleteMaterialsPasses() {
        ChildAgeReviewEvaluator.Result result = evaluate(0, 36, water(),
                List.of("CHILD_MOISTURIZING"),
                List.of(report(AgeTier.TIER_0_36, 0, 36, true)),
                "适用年龄 0-36 月（0月至36月）");

        assertThat(result.passed()).isTrue();
        assertThat(result.requiredTiers()).containsExactly(AgeTier.TIER_0_36);
    }

    @Test
    void singleTierOldWithCompleteMaterialsPasses() {
        ChildAgeReviewEvaluator.Result result = evaluate(37, 144, water(),
                List.of("CHILD_MOISTURIZING"),
                List.of(report(AgeTier.TIER_37_144, 37, 144, true)),
                "适用年龄 37-144 月");

        assertThat(result.passed()).isTrue();
        assertThat(result.requiredTiers()).containsExactly(AgeTier.TIER_37_144);
    }

    @Test
    void crossTierRangeHitsBothTiers() {
        assertThat(ChildAgeReviewEvaluator.determineTiers(0, 144))
                .containsExactly(AgeTier.TIER_0_36, AgeTier.TIER_37_144);
        // 恰跨边界：下限36上限37 也命中两档
        assertThat(ChildAgeReviewEvaluator.determineTiers(36, 37))
                .containsExactly(AgeTier.TIER_0_36, AgeTier.TIER_37_144);
        // 边界点：[0,36] 只低龄，[37,144] 只大龄
        assertThat(ChildAgeReviewEvaluator.determineTiers(0, 36))
                .containsExactly(AgeTier.TIER_0_36);
        assertThat(ChildAgeReviewEvaluator.determineTiers(37, 144))
                .containsExactly(AgeTier.TIER_37_144);
    }

    @Test
    void crossTierRequiresBothIndependentReports() {
        // 跨档 [0,144] 但只给低龄档一份报告
        ChildAgeReviewEvaluator.Result missing = evaluate(0, 144, water(),
                List.of("CHILD_MOISTURIZING"),
                List.of(report(AgeTier.TIER_0_36, 0, 36, true)),
                "适用年龄 0-144 月");

        assertThat(missing.passed()).isFalse();
        assertThat(ruleForTier(missing, "TIER_SAFETY_REPORT_PRESENT", AgeTier.TIER_37_144).passed()).isFalse();
        assertThat(ruleForTier(missing, "TIER_SAFETY_REPORT_PRESENT", AgeTier.TIER_0_36).passed()).isTrue();
        assertThat(missing.reasons()).anyMatch(s -> s.contains("TIER_37_144"));
    }

    @Test
    void crossTierWithBothReportsCoveringSlicesPasses() {
        ChildAgeReviewEvaluator.Result result = evaluate(0, 144, water(),
                List.of("CHILD_MOISTURIZING"),
                List.of(report(AgeTier.TIER_0_36, 0, 36, true),
                        report(AgeTier.TIER_37_144, 37, 144, true)),
                "适用年龄 0-144 月（出生至144月）");

        assertThat(result.passed()).isTrue();
        assertThat(rule(result, "TIER_REPORT_COVERAGE").passed()).isTrue();
    }

    @Test
    void reportMustCoverAppliedRangeWithinTier() {
        // 单档低龄申请 [0,36]，报告只声明覆盖 [0,24]，未完整覆盖
        ChildAgeReviewEvaluator.Result result = evaluate(0, 36, water(),
                List.of("CHILD_MOISTURIZING"),
                List.of(report(AgeTier.TIER_0_36, 0, 24, true)),
                "适用年龄 0-36 月");

        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "TIER_REPORT_COVERAGE").passed()).isFalse();
        assertThat(rule(result, "TIER_REPORT_COVERAGE").detail()).contains("未完整覆盖");
    }

    @Test
    void crossTierReportOnlyNeedsToCoverItsTierSlice() {
        // 跨档申请 [12,60]：低龄档切片[12,36]，大龄档切片[37,60]
        ChildAgeReviewEvaluator.Result ok = evaluate(12, 60, water(),
                List.of("CHILD_MOISTURIZING"),
                List.of(report(AgeTier.TIER_0_36, 12, 36, true),
                        report(AgeTier.TIER_37_144, 37, 60, true)),
                "适用年龄 12-60 月");
        assertThat(ok.passed()).isTrue();

        // 大龄档报告覆盖 [40,60]，缺 37-39，未覆盖该档切片起点
        ChildAgeReviewEvaluator.Result gap = evaluate(12, 60, water(),
                List.of("CHILD_MOISTURIZING"),
                List.of(report(AgeTier.TIER_0_36, 12, 36, true),
                        report(AgeTier.TIER_37_144, 40, 60, true)),
                "适用年龄 12-60 月");
        assertThat(gap.passed()).isFalse();
        assertThat(ruleForTier(gap, "TIER_REPORT_COVERAGE", AgeTier.TIER_37_144).passed()).isFalse();
        assertThat(ruleForTier(gap, "TIER_REPORT_COVERAGE", AgeTier.TIER_0_36).passed()).isTrue();
    }

    @Test
    void crossTierConcentrationUsesStrictestLimitAndEqualityPasses() {
        // 水杨酸：低龄 0.5%，大龄 1.0%；跨档取 0.5%。恰等 0.5% 合格
        List<FormulationIngredient> atStrict = List.of(
                new FormulationIngredient("SALICYLIC_ACID", "水杨酸", new BigDecimal("0.5000")));
        ChildAgeReviewEvaluator.Result pass = evaluate(0, 144, atStrict,
                List.of("CHILD_MOISTURIZING"),
                List.of(report(AgeTier.TIER_0_36, 0, 36, true),
                        report(AgeTier.TIER_37_144, 37, 144, true)),
                "适用年龄 0-144 月");
        assertThat(pass.passed()).isTrue();
        assertThat(rule(pass, "CHILD_CONCENTRATION").detail())
                .contains("较严限值 0.5%").contains("恰等合格：通过");

        // 0.5001% 超过较严限值
        List<FormulationIngredient> over = List.of(
                new FormulationIngredient("SALICYLIC_ACID", "水杨酸", new BigDecimal("0.5001")));
        ChildAgeReviewEvaluator.Result fail = evaluate(0, 144, over,
                List.of("CHILD_MOISTURIZING"),
                List.of(report(AgeTier.TIER_0_36, 0, 36, true),
                        report(AgeTier.TIER_37_144, 37, 144, true)),
                "适用年龄 0-144 月");
        assertThat(fail.passed()).isFalse();
        assertThat(rule(fail, "CHILD_CONCENTRATION").passed()).isFalse();
    }

    @Test
    void singleTierOldUsesItsOwnLooserLimit() {
        // 仅大龄档 [37,144]：水杨酸限值 1.0%，0.8% 合格（低龄档不参与）
        List<FormulationIngredient> formulation = List.of(
                new FormulationIngredient("SALICYLIC_ACID", "水杨酸", new BigDecimal("0.8000")));
        ChildAgeReviewEvaluator.Result result = evaluate(37, 144, formulation,
                List.of("CHILD_MOISTURIZING"),
                List.of(report(AgeTier.TIER_37_144, 37, 144, true)),
                "适用年龄 37-144 月");
        assertThat(result.passed()).isTrue();
        assertThat(rule(result, "CHILD_CONCENTRATION").detail()).contains("较严限值 1%");
    }

    @Test
    void adultEfficacyClaimRejectedFromChildCatalog() {
        // 成人 SUNSCREEN 不在儿童目录
        ChildAgeReviewEvaluator.Result result = evaluate(0, 36, water(),
                List.of("SUNSCREEN"),
                List.of(report(AgeTier.TIER_0_36, 0, 36, true)),
                "适用年龄 0-36 月");
        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "CHILD_CLAIM_CATALOG").passed()).isFalse();
    }

    @Test
    void unsupportedChildEfficacyRejected() {
        ChildAgeReviewEvaluator.Result result = evaluate(0, 36, water(),
                List.of("CHILD_WHITENING"),
                List.of(report(AgeTier.TIER_0_36, 0, 36, true)),
                "适用年龄 0-36 月");
        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "CHILD_CLAIM_CATALOG").detail()).contains("不允许的儿童功效宣称");
    }

    @Test
    void ageLabelMustStateExactBounds() {
        ChildAgeReviewEvaluator.Result noBounds = evaluate(0, 36, water(),
                List.of("CHILD_MOISTURIZING"),
                List.of(report(AgeTier.TIER_0_36, 0, 36, true)),
                "儿童适用（请置于阴凉处）");
        assertThat(noBounds.passed()).isFalse();
        assertThat(rule(noBounds, "AGE_LABEL").passed()).isFalse();
    }

    @Test
    void unsupportedReportConclusionFails() {
        ChildAgeReviewEvaluator.Result result = evaluate(0, 36, water(),
                List.of("CHILD_MOISTURIZING"),
                List.of(report(AgeTier.TIER_0_36, 0, 36, false)),
                "适用年龄 0-36 月");
        assertThat(result.passed()).isFalse();
        assertThat(rule(result, "TIER_REPORT_CONCLUSION").passed()).isFalse();
    }

    @Test
    void invalidAgeRangeFails() {
        ChildAgeReviewEvaluator.Result inverted = evaluate(40, 30, water(),
                List.of("CHILD_MOISTURIZING"), List.of(), "适用年龄 30-40 月");
        assertThat(inverted.passed()).isFalse();
        assertThat(rule(inverted, "AGE_RANGE_VALID").passed()).isFalse();

        ChildAgeReviewEvaluator.Result tooOld = evaluate(0, 145, water(),
                List.of("CHILD_MOISTURIZING"), List.of(), "适用年龄 0-145 月");
        assertThat(tooOld.passed()).isFalse();
        assertThat(rule(tooOld, "AGE_RANGE_VALID").passed()).isFalse();
    }

    @Test
    void multipleDeficienciesAreAllListedAtOnce() {
        // 跨档但两档报告都缺、标识缺范围、宣称非法：原因须一次列全
        ChildAgeReviewEvaluator.Result result = evaluate(0, 144, water(),
                List.of("SUNSCREEN"),
                List.of(),
                "儿童专用");
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).isNotEmpty();
        // 两档报告缺项都应出现
        assertThat(result.reasons()).anyMatch(s -> s.contains("TIER_0_36"));
        assertThat(result.reasons()).anyMatch(s -> s.contains("TIER_37_144"));
        assertThat(result.reasons()).anyMatch(s -> s.contains("CHILD_CLAIM_CATALOG"));
        assertThat(result.reasons()).anyMatch(s -> s.contains("AGE_LABEL"));
    }

    /** 与 data.sql 一致的合成儿童目录桩。 */
    private static final class StubCatalog implements ChildCatalogPort {

        private static final List<ChildEfficacy> EFFICACIES = List.of(
                new ChildEfficacy("CHILD_MOISTURIZING", "儿童保湿", true),
                new ChildEfficacy("CHILD_REFRESHING", "儿童爽肤", true),
                new ChildEfficacy("CHILD_SUNSCREEN", "儿童防晒", true),
                new ChildEfficacy("CHILD_WHITENING", "儿童美白", false));

        private static final List<ChildIngredientLimit> LIMITS = new ArrayList<>();

        static {
            LIMITS.add(new ChildIngredientLimit(RULE, AgeTier.TIER_0_36,
                    "SALICYLIC_ACID", "水杨酸", new BigDecimal("0.5000")));
            LIMITS.add(new ChildIngredientLimit(RULE, AgeTier.TIER_37_144,
                    "SALICYLIC_ACID", "水杨酸", new BigDecimal("1.0000")));
        }

        @Override
        public String currentRuleVersion() {
            return RULE;
        }

        @Override
        public List<AgeTier> tiers(String ruleVersion) {
            return List.of(AgeTier.TIER_0_36, AgeTier.TIER_37_144);
        }

        @Override
        public Optional<ChildEfficacy> findEfficacy(String code) {
            return EFFICACIES.stream().filter(e -> e.code().equals(code)).findFirst();
        }

        @Override
        public Optional<BigDecimal> findLimit(String ruleVersion, AgeTier tier, String code) {
            return LIMITS.stream()
                    .filter(l -> l.tier() == tier && l.ingredientCode().equals(code))
                    .map(ChildIngredientLimit::maxConcentrationPct)
                    .findFirst();
        }

        @Override
        public List<ChildIngredientLimit> listLimits(String ruleVersion) {
            return LIMITS;
        }
    }
}
