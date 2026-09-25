package com.example.starter.service;

import com.example.starter.domain.Grade;

import java.math.BigDecimal;

/**
 * 田间纯度与室内发芽率的分级判定规则。
 *
 * <p>原种门槛：纯度 ≥ 99.90%，发芽率 ≥ 90.00%；良种门槛：纯度 ≥ 99.00%，发芽率 ≥ 85.00%。
 * 恰好达标判合格（{@code compareTo >= 0}）；低于良种门槛判不合格并置可复检标记。
 * 两项均达原种门槛方可发原种证；两项均达良种门槛（至少一项未达原种）发良种证；
 * 任一项低于良种门槛即不得分级。
 */
public final class InspectionRules {

    /** 田间纯度原种门槛（%）。 */
    public static final BigDecimal FIELD_ORIGINAL_THRESHOLD = new BigDecimal("99.90");
    /** 田间纯度良种门槛（%）。 */
    public static final BigDecimal FIELD_BREEDER_THRESHOLD = new BigDecimal("99.00");
    /** 室内发芽率原种门槛（%）。 */
    public static final BigDecimal LAB_ORIGINAL_THRESHOLD = new BigDecimal("90.00");
    /** 室内发芽率良种门槛（%）。 */
    public static final BigDecimal LAB_BREEDER_THRESHOLD = new BigDecimal("85.00");

    private InspectionRules() {
    }

    /**
     * 单项检验值的达标判定。
     *
     * @param value             检验值（百分比）
     * @param originalThreshold 原种门槛
     * @param breederThreshold  良种门槛
     */
    public static Evaluation evaluate(BigDecimal value, BigDecimal originalThreshold,
                                      BigDecimal breederThreshold) {
        boolean passOriginal = value.compareTo(originalThreshold) >= 0;
        boolean passBreeder = value.compareTo(breederThreshold) >= 0;
        return new Evaluation(passOriginal, passBreeder, !passBreeder);
    }

    /**
     * 田间纯度判定。
     */
    public static Evaluation evaluateField(BigDecimal purity) {
        return evaluate(purity, FIELD_ORIGINAL_THRESHOLD, FIELD_BREEDER_THRESHOLD);
    }

    /**
     * 室内发芽率判定。
     */
    public static Evaluation evaluateLab(BigDecimal germinationRate) {
        return evaluate(germinationRate, LAB_ORIGINAL_THRESHOLD, LAB_BREEDER_THRESHOLD);
    }

    /**
     * 两项检验齐回后的分级结论。
     *
     * @return 两项均达原种 → ORIGINAL；两项均达良种但未同时达原种 → BREEDER；任一项低于良种 → {@code null}（不合格）
     */
    public static Grade determineGrade(Evaluation field, Evaluation lab) {
        if (field.passOriginal() && lab.passOriginal()) {
            return Grade.ORIGINAL;
        }
        if (field.passBreeder() && lab.passBreeder()) {
            return Grade.BREEDER;
        }
        return null;
    }

    /**
     * 单项检验达标结果。
     *
     * @param passOriginal 达到原种门槛
     * @param passBreeder  达到良种门槛
     * @param retestable   低于良种门槛，可复检
     */
    public record Evaluation(boolean passOriginal, boolean passBreeder, boolean retestable) {
    }
}
