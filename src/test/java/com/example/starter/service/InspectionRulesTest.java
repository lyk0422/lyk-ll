package com.example.starter.service;

import com.example.starter.domain.Grade;
import com.example.starter.service.InspectionRules.Evaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 纯度/发芽率门槛与分级规则的边界单元测试：恰好达标判合格。
 */
class InspectionRulesTest {

    @Test
    @DisplayName("田间纯度：99.90 恰好达原种门槛判合格")
    void fieldPurityExactlyOriginalThresholdPasses() {
        Evaluation evaluation = InspectionRules.evaluateField(new BigDecimal("99.90"));
        assertTrue(evaluation.passOriginal());
        assertTrue(evaluation.passBreeder());
        assertFalse(evaluation.retestable());
    }

    @Test
    @DisplayName("田间纯度：99.89 低于原种但达良种，判良种合格且不可复检")
    void fieldPurityBetweenBreederAndOriginalIsBreederOnly() {
        Evaluation evaluation = InspectionRules.evaluateField(new BigDecimal("99.89"));
        assertFalse(evaluation.passOriginal());
        assertTrue(evaluation.passBreeder());
        assertFalse(evaluation.retestable());
    }

    @Test
    @DisplayName("田间纯度：99.00 恰好达良种门槛判合格")
    void fieldPurityExactlyBreederThresholdPasses() {
        Evaluation evaluation = InspectionRules.evaluateField(new BigDecimal("99.00"));
        assertFalse(evaluation.passOriginal());
        assertTrue(evaluation.passBreeder());
        assertFalse(evaluation.retestable());
    }

    @Test
    @DisplayName("田间纯度：98.99 低于良种门槛判不合格并置可复检标记")
    void fieldPurityBelowBreederIsRetestable() {
        Evaluation evaluation = InspectionRules.evaluateField(new BigDecimal("98.99"));
        assertFalse(evaluation.passOriginal());
        assertFalse(evaluation.passBreeder());
        assertTrue(evaluation.retestable());
    }

    @Test
    @DisplayName("发芽率：90.00 恰好达原种门槛判合格")
    void germinationExactlyOriginalThresholdPasses() {
        Evaluation evaluation = InspectionRules.evaluateLab(new BigDecimal("90.00"));
        assertTrue(evaluation.passOriginal());
        assertTrue(evaluation.passBreeder());
        assertFalse(evaluation.retestable());
    }

    @Test
    @DisplayName("发芽率：85.00 恰好达良种门槛判合格")
    void germinationExactlyBreederThresholdPasses() {
        Evaluation evaluation = InspectionRules.evaluateLab(new BigDecimal("85.00"));
        assertFalse(evaluation.passOriginal());
        assertTrue(evaluation.passBreeder());
        assertFalse(evaluation.retestable());
    }

    @Test
    @DisplayName("发芽率：84.99 低于良种门槛判不合格并置可复检标记")
    void germinationBelowBreederIsRetestable() {
        Evaluation evaluation = InspectionRules.evaluateLab(new BigDecimal("84.99"));
        assertFalse(evaluation.passOriginal());
        assertFalse(evaluation.passBreeder());
        assertTrue(evaluation.retestable());
    }

    @Test
    @DisplayName("分级：两项均达原种门槛发原种")
    void gradeOriginalWhenBothPassOriginal() {
        Grade grade = InspectionRules.determineGrade(
                InspectionRules.evaluateField(new BigDecimal("99.95")),
                InspectionRules.evaluateLab(new BigDecimal("95.00")));
        assertEquals(Grade.ORIGINAL, grade);
    }

    @Test
    @DisplayName("分级：一项原种一项良种发良种，不得越级")
    void gradeBreederWhenOneOriginalOneBreeder() {
        Grade grade = InspectionRules.determineGrade(
                InspectionRules.evaluateField(new BigDecimal("99.95")),
                InspectionRules.evaluateLab(new BigDecimal("88.00")));
        assertEquals(Grade.BREEDER, grade);
    }

    @Test
    @DisplayName("分级：两项均恰好良种门槛发良种")
    void gradeBreederWhenBothExactlyBreederThresholds() {
        Grade grade = InspectionRules.determineGrade(
                InspectionRules.evaluateField(new BigDecimal("99.00")),
                InspectionRules.evaluateLab(new BigDecimal("85.00")));
        assertEquals(Grade.BREEDER, grade);
    }

    @Test
    @DisplayName("分级：任一项低于良种门槛不得分级（null），不得越级发证")
    void noGradeWhenEitherBelowBreeder() {
        assertNull(InspectionRules.determineGrade(
                InspectionRules.evaluateField(new BigDecimal("99.95")),
                InspectionRules.evaluateLab(new BigDecimal("84.99"))));
        assertNull(InspectionRules.determineGrade(
                InspectionRules.evaluateField(new BigDecimal("98.00")),
                InspectionRules.evaluateLab(new BigDecimal("99.00"))));
    }
}
