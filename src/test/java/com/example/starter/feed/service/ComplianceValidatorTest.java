package com.example.starter.feed.service;

import com.example.starter.feed.domain.FormulaItem;
import com.example.starter.feed.domain.Ingredient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 合规校验纯逻辑单测：上限恰等合法、超限拒绝、禁忌命中拒绝、含药缺物种拒绝、非含药无物种合法。
 */
class ComplianceValidatorTest {

    private static final Ingredient AMOX = new Ingredient("AMOXICILLIN", "阿莫西林", true,
            new BigDecimal("0.002"), 7);
    private static final Ingredient OTC = new Ingredient("OXYTETRACYCLINE", "土霉素", true,
            new BigDecimal("0.004"), 10);
    private static final Ingredient CORN = new Ingredient("CORN", "玉米", false, null, null);

    private static final Map<String, Ingredient> CATALOG = Map.of(
            "AMOXICILLIN", AMOX, "OXYTETRACYCLINE", OTC, "CORN", CORN);
    private static final Set<String> CONTRAPAIRS =
            Set.of(ComplianceValidator.pairKey("AMOXICILLIN", "OXYTETRACYCLINE"));

    @Test
    void ratioExactlyAtUpperBoundIsLegal() {
        ComplianceValidator.Result result = ComplianceValidator.validate(
                List.of(new FormulaItem("AMOXICILLIN", new BigDecimal("0.002"))),
                CATALOG, CONTRAPAIRS, "POULTRY");
        assertThat(result.passed()).isTrue();
        assertThat(result.reasons()).isEmpty();
        assertThat(result.medicated()).isTrue();
        assertThat(result.maxWithdrawalDays()).isEqualTo(7);
    }

    @Test
    void ratioAboveUpperBoundIsRejected() {
        ComplianceValidator.Result result = ComplianceValidator.validate(
                List.of(new FormulaItem("AMOXICILLIN", new BigDecimal("0.002001"))),
                CATALOG, CONTRAPAIRS, "POULTRY");
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("超过最大允许比例"));
    }

    @Test
    void contraindicatedPairIsRejectedRegardlessOfOrder() {
        ComplianceValidator.Result result = ComplianceValidator.validate(
                List.of(new FormulaItem("OXYTETRACYCLINE", new BigDecimal("0.001")),
                        new FormulaItem("AMOXICILLIN", new BigDecimal("0.001"))),
                CATALOG, CONTRAPAIRS, "POULTRY");
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("配伍禁忌"));
    }

    @Test
    void medicatedFormulaWithoutSpeciesIsRejected() {
        ComplianceValidator.Result result = ComplianceValidator.validate(
                List.of(new FormulaItem("AMOXICILLIN", new BigDecimal("0.001"))),
                CATALOG, CONTRAPAIRS, "  ");
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).containsExactly("含药配方必须绑定目标动物种类");
    }

    @Test
    void nonMedicatedFormulaWithoutSpeciesIsLegal() {
        ComplianceValidator.Result result = ComplianceValidator.validate(
                List.of(new FormulaItem("CORN", BigDecimal.ONE)),
                CATALOG, CONTRAPAIRS, null);
        assertThat(result.passed()).isTrue();
        assertThat(result.medicated()).isFalse();
        assertThat(result.maxWithdrawalDays()).isZero();
    }

    @Test
    void unknownIngredientIsRejected() {
        ComplianceValidator.Result result = ComplianceValidator.validate(
                List.of(new FormulaItem("GHOST_DRUG", new BigDecimal("0.001"))),
                CATALOG, CONTRAPAIRS, "POULTRY");
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("不在原料/药物添加剂目录中"));
    }
}
