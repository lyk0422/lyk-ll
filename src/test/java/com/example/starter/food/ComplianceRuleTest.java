package com.example.starter.food;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.food.domain.ComplianceItem;
import com.example.starter.food.domain.ComplianceReport;
import com.example.starter.food.domain.RecipeData;
import com.example.starter.food.service.ComplianceEvaluator;
import com.example.starter.food.web.Dtos;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 限量判定、同功能并用比例、复合配料带入折算与可复算稳定性。
 */
class ComplianceRuleTest extends AbstractFoodTest {

    @Autowired
    private ComplianceEvaluator evaluator;

    private RecipeData bev(List<RecipeData.DirectAdditive> additives, List<RecipeData.Ingredient> ingredients) {
        return new RecipeData("BEV-01", additives, ingredients == null ? List.of() : ingredients);
    }

    /** 恰等限量合法：P001 山梨酸钾在饮料中 V1 限量 0.5 g/kg，用量恰为 0.5。 */
    @Test
    void exactLimitIsCompliant() {
        RecipeData data = bev(List.of(new RecipeData.DirectAdditive("P001", new BigDecimal("0.500000"))), null);
        ComplianceReport report = evaluator.evaluate(data, "V1");
        assertTrue(report.compliant(), () -> "恰等限量应合法: " + report.items());
        ComplianceItem item = report.items().get(0);
        assertEquals(0, new BigDecimal("0.500000").compareTo(item.effectiveAmount()));
        assertEquals(0, BigDecimal.ONE.compareTo(item.ratio()));
    }

    /** 超限量拒绝。 */
    @Test
    void overLimitIsRejected() {
        RecipeData data = bev(List.of(new RecipeData.DirectAdditive("P001", new BigDecimal("0.500001"))), null);
        ComplianceReport report = evaluator.evaluate(data, "V1");
        assertFalse(report.compliant());
        assertFalse(report.items().get(0).pass());
    }

    /** 同功能两种防腐剂并用：0.25/0.5 + 0.5/1.0 = 0.5 + 0.5 = 1.0，恰等 1 合法。 */
    @Test
    void sameFunctionCombinedRatioExactlyOneIsCompliant() {
        RecipeData data = bev(List.of(
                new RecipeData.DirectAdditive("P001", new BigDecimal("0.250000")),
                new RecipeData.DirectAdditive("P002", new BigDecimal("0.500000"))), null);
        ComplianceReport report = evaluator.evaluate(data, "V1");
        assertTrue(report.compliant(), () -> "并用比例恰为 1 应合法: " + report.items());
        ComplianceItem group = report.items().stream()
                .filter(i -> i.itemType().equals("FUNCTION_GROUP")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("1.000000").compareTo(group.ratio()));
        assertTrue(group.pass());
    }

    /** 并用比例超过 1 拒绝（单项均未超限仍整单拒绝）。 */
    @Test
    void sameFunctionCombinedRatioOverOneRejected() {
        RecipeData data = bev(List.of(
                new RecipeData.DirectAdditive("P001", new BigDecimal("0.300000")),
                new RecipeData.DirectAdditive("P002", new BigDecimal("0.500000"))), null);
        ComplianceReport report = evaluator.evaluate(data, "V1");
        assertFalse(report.compliant(), "0.3/0.5 + 0.5/1.0 = 1.1 应拒绝");
        ComplianceItem group = report.items().stream()
                .filter(i -> i.itemType().equals("FUNCTION_GROUP")).findFirst().orElseThrow();
        assertEquals(1, group.ratio().compareTo(BigDecimal.ONE));
    }

    /** 复合配料带入折算：果酱占比 0.4，果酱含 P001 1.0 g/kg => 带入 0.4；未超限合法。 */
    @Test
    void carryOverWithinLimitIsCompliant() {
        RecipeData data = bev(List.of(),
                List.of(new RecipeData.Ingredient("C001", new BigDecimal("0.400000"))));
        ComplianceReport report = evaluator.evaluate(data, "V1");
        assertTrue(report.compliant(), () -> "带入 0.4 ≤ 0.5 应合法: " + report.items());
        ComplianceItem item = report.items().get(0);
        assertEquals(0, new BigDecimal("0.400000").compareTo(item.carryOverAmount()));
        assertEquals(0, new BigDecimal("0.400000").compareTo(item.effectiveAmount()));
        assertEquals("复合配料带入", item.source());
    }

    /** 带入折算超限：果酱占比 0.6 => 带入 0.6 > 0.5，终产品超限拒绝。 */
    @Test
    void carryOverOverLimitIsRejected() {
        RecipeData data = bev(List.of(),
                List.of(new RecipeData.Ingredient("C001", new BigDecimal("0.600000"))));
        ComplianceReport report = evaluator.evaluate(data, "V1");
        assertFalse(report.compliant());
        ComplianceItem item = report.items().get(0);
        assertEquals(0, new BigDecimal("0.600000").compareTo(item.effectiveAmount()));
        assertFalse(item.pass());
    }

    /** 直接加入与带入合并计：直接 0.3 + 带入 0.4*0.5=0.2 => 0.5 恰等限量合法。 */
    @Test
    void directPlusCarryOverExactLimit() {
        RecipeData data = bev(List.of(new RecipeData.DirectAdditive("P001", new BigDecimal("0.300000"))),
                List.of(new RecipeData.Ingredient("C001", new BigDecimal("0.200000"))));
        ComplianceReport report = evaluator.evaluate(data, "V1");
        assertTrue(report.compliant(), () -> "直接 0.3 + 带入 0.2 = 0.5 应合法: " + report.items());
        assertEquals(0, new BigDecimal("0.500000").compareTo(report.items().get(0).effectiveAmount()));
    }

    /** 端到端：审查明细落库且可由落库配方复算，重复查询结果稳定。 */
    @Test
    void reviewPersistsItemsAndRecomputeIsStable() {
        Dtos.RecipeResponse recipe = submit("req-stable-1", "PRD-S", "v1", "BEV-01",
                List.of(add("P001", "0.500000")), null);
        Dtos.RecipeResponse reviewed = review("rev-stable-1", recipe.id(), "V1");
        assertEquals("COMPLIANT", reviewed.status());
        assertTrue(reviewed.compliant());
        assertEquals(1, reviewed.reviewItems().size());

        Dtos.RecipeResponse again = service.getRecipe(recipe.id());
        assertEquals(reviewed.reviewItems().size(), again.reviewItems().size());
        assertEquals(reviewed.reviewItems().get(0).ratio(), again.reviewItems().get(0).ratio());

        // 由 DB 数据重新复算，结论与逐项比例一致
        RecipeData data = new RecipeData(recipe.categoryCode(),
                List.of(new RecipeData.DirectAdditive("P001", new BigDecimal("0.500000"))), List.of());
        ComplianceReport recomputed = evaluator.evaluate(data, "V1");
        assertTrue(recomputed.compliant());
        assertEquals(0, recomputed.items().get(0).ratio().compareTo(reviewed.reviewItems().get(0).ratio()));
    }

    /** 任一违例整单拒绝，状态为 REJECTED。 */
    @Test
    void anyViolationRejectsWholeRecipe() {
        Dtos.RecipeResponse recipe = submit("req-rej-1", "PRD-R", "v1", "BEV-01",
                List.of(add("P001", "9.000000")), null);
        Dtos.RecipeResponse reviewed = review("rev-rej-1", recipe.id(), "V1");
        assertEquals("REJECTED", reviewed.status());
        assertFalse(reviewed.compliant());
    }
}
