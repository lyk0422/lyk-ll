package com.example.starter.catalog;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检验判定测试：恰等限值判合格，越界判不合格。
 */
class InspectionEvaluatorTest {

    @Test
    void potencyExactlyAtLowerLimitIsQualified() {
        InspectionSpec potency = InspectionCatalog.find(InspectionCatalog.POTENCY);
        assertTrue(InspectionEvaluator.isQualified(potency, "4.0"), "效力恰等限值 4.0 判合格");
        assertTrue(InspectionEvaluator.isQualified(potency, "4.00"));
        assertFalse(InspectionEvaluator.isQualified(potency, "3.99"), "效力低于限值判不合格");
    }

    @Test
    void abnormalToxicityExactlyAtUpperLimitIsQualified() {
        InspectionSpec toxicity = InspectionCatalog.find(InspectionCatalog.ABNORMAL_TOXICITY);
        assertTrue(InspectionEvaluator.isQualified(toxicity, "0.10"), "异常毒性恰等限值 0.10 判合格");
        assertFalse(InspectionEvaluator.isQualified(toxicity, "0.11"), "异常毒性高于限值判不合格");
    }

    @Test
    void sterilityTextBoundary() {
        InspectionSpec sterility = InspectionCatalog.find(InspectionCatalog.STERILITY);
        assertTrue(InspectionEvaluator.isQualified(sterility, "无菌"));
        assertFalse(InspectionEvaluator.isQualified(sterility, "有菌"));
    }

    @Test
    void phRangeIncludesBothEndpoints() {
        InspectionSpec ph = InspectionCatalog.find(InspectionCatalog.PH_VALUE);
        assertTrue(InspectionEvaluator.isQualified(ph, "7.0"));
        assertTrue(InspectionEvaluator.isQualified(ph, "8.0"));
        assertFalse(InspectionEvaluator.isQualified(ph, "6.9"));
        assertFalse(InspectionEvaluator.isQualified(ph, "8.1"));
    }

    @Test
    void moistureExactlyAtUpperLimitIsQualified() {
        InspectionSpec moisture = InspectionCatalog.find(InspectionCatalog.MOISTURE);
        assertTrue(InspectionEvaluator.isQualified(moisture, "3.0"));
        assertFalse(InspectionEvaluator.isQualified(moisture, "3.01"));
    }

    @Test
    void unparseableValueIsNotQualified() {
        InspectionSpec potency = InspectionSpec.gte("X", "x", true, new BigDecimal("1"));
        assertFalse(InspectionEvaluator.isQualified(potency, "无法解析"));
        assertFalse(InspectionEvaluator.isQualified(potency, null));
    }
}
