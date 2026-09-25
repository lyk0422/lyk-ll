package com.example.starter.ili;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 分级策略单元测试：年化增长率计算与 5%/15% 阈值口径（达到或超过才升级）。
 */
class GradePolicyTest {

    private static final LocalDate D1 = LocalDate.of(2023, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2024, 1, 1); // 恰好 365 天

    private static GradePolicy.Grade gradeOf(String oldDepth, String newDepth) {
        BigDecimal rate = GradePolicy.annualGrowthRate(new BigDecimal(oldDepth), new BigDecimal(newDepth),
                D1, D2);
        return GradePolicy.grade(rate);
    }

    @Test
    void belowFivePercentIsStable() {
        assertEquals(GradePolicy.Grade.STABLE, gradeOf("10", "14.999"));
        assertEquals(GradePolicy.Grade.STABLE, gradeOf("10", "10"));
    }

    @Test
    void negativeGrowthIsStable() {
        assertEquals(GradePolicy.Grade.STABLE, gradeOf("20", "12"));
    }

    @Test
    void exactlyFivePercentIsGrowing() {
        // 达到 5%/年 即判增长
        assertEquals(GradePolicy.Grade.GROWING, gradeOf("10", "15"));
    }

    @Test
    void belowFifteenPercentIsGrowing() {
        assertEquals(GradePolicy.Grade.GROWING, gradeOf("10", "24.999"));
    }

    @Test
    void exactlyFifteenPercentIsSevere() {
        // 达到 15%/年 即判严重
        assertEquals(GradePolicy.Grade.SEVERE, gradeOf("10", "25"));
    }

    @Test
    void aboveFifteenPercentIsSevere() {
        assertEquals(GradePolicy.Grade.SEVERE, gradeOf("10", "40"));
    }

    @Test
    void annualizesOverFractionalYear() {
        // 182 天增长 2.5 个百分点 -> 年化 2.5*365/182 = 5.0137 -> 增长
        BigDecimal rate = GradePolicy.annualGrowthRate(new BigDecimal("10"), new BigDecimal("12.5"),
                LocalDate.of(2023, 1, 1), LocalDate.of(2023, 7, 2));
        assertEquals(0, new BigDecimal("5.0137").compareTo(rate));
        assertEquals(GradePolicy.Grade.GROWING, GradePolicy.grade(rate));
    }

    @Test
    void annualizesDownwardOverMultipleYears() {
        // 730 天（2023-01-01 至 2024-12-31）增长 10 个百分点 -> 年化 5.0 -> 增长（恰好达到阈值）
        BigDecimal rate = GradePolicy.annualGrowthRate(new BigDecimal("10"), new BigDecimal("20"),
                LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31));
        assertEquals(0, new BigDecimal("5.0000").compareTo(rate));
        assertEquals(GradePolicy.Grade.GROWING, GradePolicy.grade(rate));
    }

    @Test
    void rejectsNonPositiveDateGap() {
        assertThrows(IllegalArgumentException.class, () -> GradePolicy.annualGrowthRate(
                new BigDecimal("10"), new BigDecimal("12"), D1, D1));
        assertThrows(IllegalArgumentException.class, () -> GradePolicy.annualGrowthRate(
                new BigDecimal("10"), new BigDecimal("12"), D2, D1));
    }
}
