package com.example.starter.ili;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.ili.AlignmentService.AlignmentResult;
import com.example.starter.ili.AlignmentService.ReadingInput;

/**
 * 对齐与分级纯逻辑测试：容差边界、一对多取最小、分级阈值口径。
 */
class AlignmentServiceTest {

    private final AlignmentService service = new AlignmentService();

    /** 2020-01-01 至 2024-01-01 恰为 1461 天 = 4 年（365.25 天/年），便于构造整阈值增长率。 */
    private static final LocalDate OLD_DATE = LocalDate.of(2020, 1, 1);
    private static final LocalDate NEW_DATE = LocalDate.of(2024, 1, 1);

    private static ReadingInput reading(String ref, String position, String depth) {
        return new ReadingInput(ref, new BigDecimal(position), new BigDecimal(depth));
    }

    @Test
    void distanceExactlyAtToleranceMatches() {
        AlignmentResult result = service.align(
                List.of(reading("O1", "100.000", "10")),
                List.of(reading("N1", "100.500", "10")),
                OLD_DATE, NEW_DATE);

        assertEquals(1, result.pairs().size());
        assertEquals(0, new BigDecimal("0.500").compareTo(result.pairs().get(0).distanceM()));
        assertTrue(result.newFindingIndexes().isEmpty());
        assertTrue(result.disappearedIndexes().isEmpty());
    }

    @Test
    void distanceBeyondToleranceDoesNotMatch() {
        AlignmentResult result = service.align(
                List.of(reading("O1", "100.000", "10")),
                List.of(reading("N1", "100.501", "10")),
                OLD_DATE, NEW_DATE);

        assertTrue(result.pairs().isEmpty());
        assertEquals(List.of(0), result.newFindingIndexes());
        assertEquals(List.of(0), result.disappearedIndexes());
    }

    @Test
    void oneToManyPicksSmallestDistance() {
        // 新读数 N1 同时落在 O1（差 0.3）与 O2（差 0.1）容差内，应取里程差最小的 O2。
        AlignmentResult result = service.align(
                List.of(reading("O1", "100.000", "10"), reading("O2", "100.400", "10")),
                List.of(reading("N1", "100.300", "10")),
                OLD_DATE, NEW_DATE);

        assertEquals(1, result.pairs().size());
        assertEquals(1, result.pairs().get(0).oldIndex());
        assertEquals(0, result.pairs().get(0).newIndex());
        assertEquals(List.of(0), result.disappearedIndexes());
        assertTrue(result.newFindingIndexes().isEmpty());
    }

    @Test
    void oneOldNearTwoNewPicksSmallestDistance() {
        // 旧读数 O1 同时落在 N1（差 0.1）与 N2（差 0.4）容差内，应取 N1；N2 记为新发现。
        AlignmentResult result = service.align(
                List.of(reading("O1", "100.000", "10")),
                List.of(reading("N1", "100.100", "10"), reading("N2", "100.400", "10")),
                OLD_DATE, NEW_DATE);

        assertEquals(1, result.pairs().size());
        assertEquals(0, result.pairs().get(0).newIndex());
        assertEquals(List.of(1), result.newFindingIndexes());
        assertTrue(result.disappearedIndexes().isEmpty());
    }

    @Test
    void sameDefectNeverAppearsInTwoPairs() {
        // 两个旧读数都在同一新读数容差内，只能产生一个匹配对。
        AlignmentResult result = service.align(
                List.of(reading("O1", "100.100", "10"), reading("O2", "100.200", "10")),
                List.of(reading("N1", "100.150", "10")),
                OLD_DATE, NEW_DATE);

        assertEquals(1, result.pairs().size());
        assertEquals(1, result.disappearedIndexes().size());
    }

    @Test
    void gradingThresholdsAreInclusiveAtFiveAndFifteen() {
        // 间隔 4 年：深度差 20 -> 5.0%/年 = 增长；深度差 60 -> 15.0%/年 = 严重。
        AlignmentResult result = service.align(
                List.of(reading("O1", "100.0", "10"), reading("O2", "200.0", "10"),
                        reading("O3", "300.0", "10"), reading("O4", "400.0", "80")),
                List.of(reading("N1", "100.0", "29.9"), reading("N2", "200.0", "30"),
                        reading("N3", "300.0", "69.9"), reading("N4", "400.0", "20")),
                OLD_DATE, NEW_DATE);

        assertEquals(4, result.pairs().size());
        // 19.9/4 = 4.975 < 5 -> 稳定
        assertEquals(Grade.STABLE, result.pairs().get(0).grade());
        // 20/4 = 5.0 -> 增长（达到 5% 即增长）
        assertEquals(Grade.GROWING, result.pairs().get(1).grade());
        // 59.9/4 = 14.975 < 15 -> 增长
        assertEquals(Grade.GROWING, result.pairs().get(2).grade());
        // -60/4 = -15 -> 负增长仍为稳定
        assertEquals(Grade.STABLE, result.pairs().get(3).grade());
    }

    @Test
    void severeAtExactlyFifteenPercent() {
        AlignmentResult result = service.align(
                List.of(reading("O1", "100.0", "10")),
                List.of(reading("N1", "100.0", "70")),
                OLD_DATE, NEW_DATE);

        assertEquals(Grade.SEVERE, result.pairs().get(0).grade());
        assertEquals(0, new BigDecimal("15.0000").compareTo(result.pairs().get(0).growthRatePctPerYear()));
    }

    @Test
    void rejectsNonPositiveInterval() {
        assertThrows(IllegalArgumentException.class, () -> service.align(
                List.of(reading("O1", "100.0", "10")),
                List.of(reading("N1", "100.0", "10")),
                NEW_DATE, OLD_DATE));
    }
}
