package com.example.starter.compliance.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.compliance.domain.DirectAdditive;
import com.example.starter.compliance.domain.IngredientUse;
import com.example.starter.compliance.engine.ReviewModels.GroupResult;
import com.example.starter.compliance.engine.ReviewModels.ItemResult;
import com.example.starter.compliance.engine.ReviewModels.ReviewReport;
import com.example.starter.compliance.repo.AdditiveRow;
import com.example.starter.compliance.repo.IngredientAdditiveRow;
import com.example.starter.compliance.repo.LimitRow;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 限量判定引擎纯单元测试：恰等限量、同功能并用比例恰 1、复合配料带入折算、无限量拒绝与可复算稳定性。
 */
class LimitEngineTest {

    private final LimitEngine engine = new LimitEngine();

    private static AdditiveRow additive(String code, String function) {
        return new AdditiveRow(code, code + "-name", function);
    }

    private static LimitRow limit(String code, String max) {
        return new LimitRow(code, "BEV01", new BigDecimal(max), 0L);
    }

    private static DirectAdditive direct(String code, String amount) {
        return new DirectAdditive(code, new BigDecimal(amount));
    }

    private static IngredientUse ingredient(String code, String ratio) {
        return new IngredientUse(code, new BigDecimal(ratio));
    }

    private ReviewInput input(List<DirectAdditive> direct, List<IngredientUse> ingredients,
            Map<String, AdditiveRow> catalog,
            Map<String, List<IngredientAdditiveRow>> ingredientAdditives,
            Map<String, LimitRow> limits) {
        return new ReviewInput("BEV01", direct, ingredients, catalog,
                Set.copyOf(ingredientAdditives.keySet()), ingredientAdditives, limits);
    }

    @Test
    void exactLimitIsLegal() {
        // 终产品 E202 含量恰等限量 0.5 g/kg：合法（恰等限量合法）。
        Map<String, AdditiveRow> catalog = Map.of("E202", additive("E202", "PRESERVATIVE"));
        Map<String, LimitRow> limits = Map.of("E202", limit("E202", "0.5"));
        ReviewInput in = input(List.of(direct("E202", "0.5")), List.of(), catalog, Map.of(), limits);

        ReviewReport report = engine.evaluate(in, 123L);

        assertThat(report.passed()).isTrue();
        ItemResult item = report.items().get(0);
        assertThat(item.totalAmount()).isEqualByComparingTo("0.500000");
        assertThat(item.limitAmount()).isEqualByComparingTo("0.500000");
        assertThat(item.usageRatio()).isEqualByComparingTo("1.000000");
        assertThat(item.reason()).isEqualTo("OK");
    }

    @Test
    void oneOverLimitIsRejected() {
        Map<String, AdditiveRow> catalog = Map.of("E202", additive("E202", "PRESERVATIVE"));
        Map<String, LimitRow> limits = Map.of("E202", limit("E202", "0.5"));
        ReviewInput in = input(List.of(direct("E202", "0.500001")), List.of(), catalog,
                Map.of(), limits);

        ReviewReport report = engine.evaluate(in, 1L);

        assertThat(report.passed()).isFalse();
        assertThat(report.items().get(0).reason()).isEqualTo("OVER_LIMIT");
    }

    @Test
    void sameFunctionCombinedRatioExactlyOneIsLegal() {
        // 两种防腐剂并用：E202 用量 0.25（限量 0.5，比例 0.5）+ E211 用量 0.5（限量 1.0，比例 0.5），
        // 比例之和恰等 1：合法；各自单项也不超限。
        Map<String, AdditiveRow> catalog = new LinkedHashMap<>();
        catalog.put("E202", additive("E202", "PRESERVATIVE"));
        catalog.put("E211", additive("E211", "PRESERVATIVE"));
        Map<String, LimitRow> limits = new LinkedHashMap<>();
        limits.put("E202", limit("E202", "0.5"));
        limits.put("E211", limit("E211", "1.0"));
        ReviewInput in = input(List.of(direct("E202", "0.25"), direct("E211", "0.5")),
                List.of(), catalog, Map.of(), limits);

        ReviewReport report = engine.evaluate(in, 1L);

        assertThat(report.passed()).isTrue();
        GroupResult group = report.groups().get(0);
        assertThat(group.functionCode()).isEqualTo("PRESERVATIVE");
        assertThat(group.combinedRatio()).isEqualByComparingTo("1.000000");
        assertThat(group.passed()).isTrue();
    }

    @Test
    void sameFunctionCombinedRatioOverOneIsRejected() {
        // 0.3/0.5=0.6 与 0.5/1.0=0.5，并用比例之和 1.1 > 1：整单拒绝（单项均不超限）。
        Map<String, AdditiveRow> catalog = new LinkedHashMap<>();
        catalog.put("E202", additive("E202", "PRESERVATIVE"));
        catalog.put("E211", additive("E211", "PRESERVATIVE"));
        Map<String, LimitRow> limits = new LinkedHashMap<>();
        limits.put("E202", limit("E202", "0.5"));
        limits.put("E211", limit("E211", "1.0"));
        ReviewInput in = input(List.of(direct("E202", "0.3"), direct("E211", "0.5")),
                List.of(), catalog, Map.of(), limits);

        ReviewReport report = engine.evaluate(in, 1L);

        assertThat(report.passed()).isFalse();
        assertThat(report.items()).allSatisfy(item -> assertThat(item.passed()).isTrue());
        assertThat(report.groups()).hasSize(1);
        assertThat(report.groups().get(0).passed()).isFalse();
        assertThat(report.groups().get(0).combinedRatio()).isEqualByComparingTo("1.100000");
    }

    @Test
    void carriedOverAmountWithinLimitIsLegal() {
        // 配料占比 0.2，配料中 E202 本底 2.0 g/kg → 终产品带入 0.4 ≤ 限量 0.5：合法。
        Map<String, AdditiveRow> catalog = new LinkedHashMap<>();
        catalog.put("E202", additive("E202", "PRESERVATIVE"));
        Map<String, LimitRow> limits = Map.of("E202", limit("E202", "0.5"));
        Map<String, List<IngredientAdditiveRow>> ingredients = Map.of(
                "ING_SAUCE", List.of(new IngredientAdditiveRow("ING_SAUCE", "E202",
                        new BigDecimal("2.0"))));
        ReviewInput in = input(List.of(), List.of(ingredient("ING_SAUCE", "0.2")), catalog,
                ingredients, limits);

        ReviewReport report = engine.evaluate(in, 1L);

        assertThat(report.passed()).isTrue();
        ItemResult item = report.items().get(0);
        assertThat(item.directAmount()).isEqualByComparingTo("0");
        assertThat(item.carriedAmount()).isEqualByComparingTo("0.400000");
        assertThat(item.totalAmount()).isEqualByComparingTo("0.400000");
    }

    @Test
    void carriedOverAmountOverLimitIsRejected() {
        // 配料占比 0.3，本底 2.0 g/kg → 带入 0.6 > 限量 0.5：终产品含量超限拒绝。
        Map<String, AdditiveRow> catalog = new LinkedHashMap<>();
        catalog.put("E202", additive("E202", "PRESERVATIVE"));
        Map<String, LimitRow> limits = Map.of("E202", limit("E202", "0.5"));
        Map<String, List<IngredientAdditiveRow>> ingredients = Map.of(
                "ING_SAUCE", List.of(new IngredientAdditiveRow("ING_SAUCE", "E202",
                        new BigDecimal("2.0"))));
        ReviewInput in = input(List.of(), List.of(ingredient("ING_SAUCE", "0.3")), catalog,
                ingredients, limits);

        ReviewReport report = engine.evaluate(in, 1L);

        assertThat(report.passed()).isFalse();
        assertThat(report.items().get(0).reason()).isEqualTo("OVER_LIMIT");
        assertThat(report.items().get(0).carriedAmount()).isEqualByComparingTo("0.600000");
    }

    @Test
    void directPlusCarriedSumExceedingLimitIsRejected() {
        // 直接 0.3 + 带入 0.2×1.0=0.2 → 总量 0.5 恰等限量合法；再验 0.31 直接量即超限。
        Map<String, AdditiveRow> catalog = new LinkedHashMap<>();
        catalog.put("E202", additive("E202", "PRESERVATIVE"));
        Map<String, LimitRow> limits = Map.of("E202", limit("E202", "0.5"));
        Map<String, List<IngredientAdditiveRow>> ingredients = Map.of(
                "ING_SAUCE", List.of(new IngredientAdditiveRow("ING_SAUCE", "E202",
                        new BigDecimal("1.0"))));
        ReviewInput exact = input(List.of(direct("E202", "0.3")),
                List.of(ingredient("ING_SAUCE", "0.2")), catalog, ingredients, limits);
        assertThat(engine.evaluate(exact, 1L).passed()).isTrue();

        ReviewInput over = input(List.of(direct("E202", "0.31")),
                List.of(ingredient("ING_SAUCE", "0.2")), catalog, ingredients, limits);
        ReviewReport report = engine.evaluate(over, 1L);
        assertThat(report.passed()).isFalse();
        assertThat(report.items().get(0).totalAmount()).isEqualByComparingTo("0.510000");
    }

    @Test
    void additiveWithoutLimitIsRejected() {
        Map<String, AdditiveRow> catalog = Map.of("E999", additive("E999", "OTHER"));
        ReviewInput in = input(List.of(direct("E999", "0.01")), List.of(), catalog, Map.of(),
                Map.of());

        ReviewReport report = engine.evaluate(in, 1L);

        assertThat(report.passed()).isFalse();
        assertThat(report.items().get(0).reason()).isEqualTo("LIMIT_NOT_DEFINED");
    }

    @Test
    void evaluationIsDeterministicAndReproducible() {
        Map<String, AdditiveRow> catalog = new LinkedHashMap<>();
        catalog.put("E202", additive("E202", "PRESERVATIVE"));
        catalog.put("E211", additive("E211", "PRESERVATIVE"));
        Map<String, LimitRow> limits = new LinkedHashMap<>();
        limits.put("E202", limit("E202", "0.5"));
        limits.put("E211", limit("E211", "1.0"));
        ReviewInput in = input(List.of(direct("E211", "0.5"), direct("E202", "0.25")),
                List.of(), catalog, Map.of(), limits);

        ReviewReport first = engine.evaluate(in, 7L);
        ReviewReport second = engine.evaluate(in, 7L);

        assertThat(second).usingRecursiveComparison().isEqualTo(first);
        // 明细按添加剂编码稳定排序，支持逐项复算。
        assertThat(first.items().stream().map(ItemResult::additiveCode).toList())
                .containsExactly("E202", "E211");
    }
}
