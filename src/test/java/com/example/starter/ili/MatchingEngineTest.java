package com.example.starter.ili;

import com.example.starter.ili.model.AlignmentOutcome;
import com.example.starter.ili.model.Grade;
import com.example.starter.ili.model.MatchResult;
import com.example.starter.ili.model.ReadingInput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匹配引擎单元测试：容差边界、一对多取最小、读数唯一性、分级阈值口径。
 */
class MatchingEngineTest {

    private final MatchingEngine engine = new MatchingEngine();

    /** 两回合间隔 1461 天 = 4 年整（含一个闰年），便于增长率整除。 */
    private static final LocalDate OLD_DATE = LocalDate.of(2020, 1, 1);
    private static final LocalDate NEW_DATE = LocalDate.of(2024, 1, 1);

    private static ReadingInput reading(String key, String station, String depth) {
        return new ReadingInput(key, new BigDecimal(station), new BigDecimal(depth));
    }

    @Test
    void matchesWhenDistanceExactlyAtToleranceBoundary() {
        AlignmentOutcome outcome = engine.align(
                List.of(reading("o1", "100.000", "10")),
                List.of(reading("n1", "100.500", "12")),
                OLD_DATE, NEW_DATE);

        assertEquals(1, outcome.matches().size());
        assertEquals(0, new BigDecimal("0.500").compareTo(outcome.matches().get(0).distanceM()));
        assertTrue(outcome.newFindings().isEmpty());
        assertTrue(outcome.disappeared().isEmpty());
    }

    @Test
    void rejectsWhenDistanceExceedsTolerance() {
        AlignmentOutcome outcome = engine.align(
                List.of(reading("o1", "100.000", "10")),
                List.of(reading("n1", "100.501", "12")),
                OLD_DATE, NEW_DATE);

        assertTrue(outcome.matches().isEmpty());
        assertEquals(List.of("n1"), outcome.newFindings());
        assertEquals(List.of("o1"), outcome.disappeared());
    }

    @Test
    void oneToManyPicksSmallestDistance() {
        AlignmentOutcome outcome = engine.align(
                List.of(reading("o1", "100.000", "10")),
                List.of(reading("n-far", "100.400", "12"),
                        reading("n-near", "100.100", "12")),
                OLD_DATE, NEW_DATE);

        assertEquals(1, outcome.matches().size());
        assertEquals("n-near", outcome.matches().get(0).newKey());
        assertEquals(List.of("n-far"), outcome.newFindings());
    }

    @Test
    void readingNeverAppearsInTwoPairs() {
        // 两个旧读数都在同一个新读数容差内：仅里程差最小者成对
        AlignmentOutcome outcome = engine.align(
                List.of(reading("o-far", "100.400", "10"),
                        reading("o-near", "100.100", "10")),
                List.of(reading("n1", "100.000", "12")),
                OLD_DATE, NEW_DATE);

        assertEquals(1, outcome.matches().size());
        assertEquals("o-near", outcome.matches().get(0).oldKey());
        assertEquals(List.of("o-far"), outcome.disappeared());
        assertTrue(outcome.newFindings().isEmpty());
    }

    @Test
    void gradingThresholdsAreLowerInclusive() {
        assertEquals(Grade.STABLE, engine.gradeOf(new BigDecimal("-3.5")));
        assertEquals(Grade.STABLE, engine.gradeOf(new BigDecimal("4.9999")));
        assertEquals(Grade.GROWING, engine.gradeOf(new BigDecimal("5.0000")));
        assertEquals(Grade.GROWING, engine.gradeOf(new BigDecimal("14.9999")));
        assertEquals(Grade.SEVERE, engine.gradeOf(new BigDecimal("15.0000")));
        assertEquals(Grade.SEVERE, engine.gradeOf(new BigDecimal("40")));
    }

    @Test
    void growthRateUsesInspectionDateInterval() {
        // 4 年增长 20% → 5.0000 %/年 → GROWING；增长 60% → 15 %/年 → SEVERE
        AlignmentOutcome outcome = engine.align(
                List.of(reading("o1", "100.0", "10"), reading("o2", "200.0", "10")),
                List.of(reading("n1", "100.0", "30"), reading("n2", "200.0", "70")),
                OLD_DATE, NEW_DATE);

        MatchResult m1 = outcome.matches().stream().filter(m -> m.oldKey().equals("o1")).findFirst().orElseThrow();
        MatchResult m2 = outcome.matches().stream().filter(m -> m.oldKey().equals("o2")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("5.0000").compareTo(m1.growthRatePct()));
        assertEquals(Grade.GROWING, m1.grade());
        assertEquals(0, new BigDecimal("15.0000").compareTo(m2.growthRatePct()));
        assertEquals(Grade.SEVERE, m2.grade());
    }

    @Test
    void rejectsNonPositiveInspectionInterval() {
        assertThrows(IllegalArgumentException.class, () -> engine.align(
                List.of(), List.of(), NEW_DATE, OLD_DATE));
    }
}
