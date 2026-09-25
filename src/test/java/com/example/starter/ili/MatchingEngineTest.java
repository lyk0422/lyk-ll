package com.example.starter.ili;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐引擎单元测试：容差边界、一对多取最小、唯一匹配约束与未匹配归类。
 */
class MatchingEngineTest {

    private static MatchingEngine.DefectInput defect(String ref, String mileage) {
        return new MatchingEngine.DefectInput(ref, new BigDecimal(mileage), new BigDecimal("10"));
    }

    @Test
    void matchesWhenDiffExactlyAtToleranceBoundary() {
        // 里程差恰好 0.5m 视为匹配成功
        var result = MatchingEngine.match(List.of(defect("O1", "100.0")), List.of(defect("N1", "100.5")));
        assertEquals(1, result.pairs().size());
        assertEquals(0, new BigDecimal("0.5").compareTo(result.pairs().get(0).mileageDiffM()));
        assertTrue(result.newFindings().isEmpty());
        assertTrue(result.disappeared().isEmpty());
    }

    @Test
    void matchesWhenDiffExactlyAtToleranceBoundaryNegativeSide() {
        var result = MatchingEngine.match(List.of(defect("O1", "100.0")), List.of(defect("N1", "99.5")));
        assertEquals(1, result.pairs().size());
    }

    @Test
    void rejectsWhenDiffExceedsTolerance() {
        // 0.500...1m 超出容差：旧缺陷记已消失，新读数记新发现
        var result = MatchingEngine.match(List.of(defect("O1", "100.0")),
                List.of(defect("N1", "100.5001")));
        assertTrue(result.pairs().isEmpty());
        assertEquals(List.of("N1"), result.newFindings().stream().map(MatchingEngine.DefectInput::defectRef).toList());
        assertEquals(List.of("O1"), result.disappeared().stream().map(MatchingEngine.DefectInput::defectRef).toList());
    }

    @Test
    void oneToManyPicksSmallestMileageDiff() {
        // 一个旧缺陷对两个新读数：取里程差最小者，剩余新读数记新发现
        var result = MatchingEngine.match(List.of(defect("O1", "100.0")),
                List.of(defect("N1", "100.4"), defect("N2", "99.8")));
        assertEquals(1, result.pairs().size());
        assertEquals("N2", result.pairs().get(0).newDefect().defectRef());
        assertEquals(List.of("N1"), result.newFindings().stream().map(MatchingEngine.DefectInput::defectRef).toList());
        assertTrue(result.disappeared().isEmpty());
    }

    @Test
    void oneNewReadingMatchesOnlyClosestOldDefect() {
        // 一个新读数对两个旧缺陷：只允许进入里程差最小的一个匹配对
        var result = MatchingEngine.match(List.of(defect("O1", "100.3"), defect("O2", "99.9")),
                List.of(defect("N1", "100.0")));
        assertEquals(1, result.pairs().size());
        assertEquals("O2", result.pairs().get(0).oldDefect().defectRef());
        assertEquals(List.of("O1"), result.disappeared().stream().map(MatchingEngine.DefectInput::defectRef).toList());
        assertTrue(result.newFindings().isEmpty());
    }

    @Test
    void greedyMatchingKeepsEachDefectInAtMostOnePair() {
        // O1-N1 差 0.1 最优；O2-N1 差 0.3 但 N1 已被占用，O2 退而匹配 N2（差 0.4）；
        // N1 不得出现在两个匹配对中
        var result = MatchingEngine.match(List.of(defect("O1", "100.0"), defect("O2", "100.4")),
                List.of(defect("N1", "100.1"), defect("N2", "100.8")));
        assertEquals(2, result.pairs().size());
        var byOld = new java.util.HashMap<String, String>();
        result.pairs().forEach(p -> byOld.put(p.oldDefect().defectRef(), p.newDefect().defectRef()));
        assertEquals("N1", byOld.get("O1"));
        assertEquals("N2", byOld.get("O2"));
        assertTrue(result.newFindings().isEmpty());
        assertTrue(result.disappeared().isEmpty());
    }

    @Test
    void unmatchedOnBothSidesAreClassified() {
        var result = MatchingEngine.match(List.of(defect("O1", "100.0"), defect("O2", "500.0")),
                List.of(defect("N1", "100.2"), defect("N2", "900.0")));
        assertEquals(1, result.pairs().size());
        assertEquals(List.of("N2"), result.newFindings().stream().map(MatchingEngine.DefectInput::defectRef).toList());
        assertEquals(List.of("O2"), result.disappeared().stream().map(MatchingEngine.DefectInput::defectRef).toList());
    }
}
