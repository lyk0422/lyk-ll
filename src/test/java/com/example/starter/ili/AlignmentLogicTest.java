package com.example.starter.ili;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.ili.AlignmentService.AlignedPair;
import com.example.starter.ili.BatchSubmitRequest.DefectReading;
import com.example.starter.ili.BatchSubmitRequest.RunDefects;

/**
 * 对齐、分级与指纹的纯逻辑单元测试（不启动 Spring 上下文）。
 */
class AlignmentLogicTest {

    private static DefectReading defect(String ref, String mileage, String depth) {
        return new DefectReading(ref, new BigDecimal(mileage), new BigDecimal(depth));
    }

    @Test
    void 里程差恰好等于容差时匹配成功() {
        List<AlignedPair> pairs = AlignmentService.align(
                List.of(defect("O1", "100.000", "10")),
                List.of(defect("N1", "100.500", "12")));
        assertEquals(1, pairs.size());
        assertEquals(0, new BigDecimal("0.5").compareTo(pairs.get(0).diff()));
    }

    @Test
    void 里程差超出容差时不匹配() {
        List<AlignedPair> pairs = AlignmentService.align(
                List.of(defect("O1", "100.000", "10")),
                List.of(defect("N1", "100.501", "12")));
        assertTrue(pairs.isEmpty());
    }

    @Test
    void 一对多匹配取里程差最小者() {
        List<AlignedPair> pairs = AlignmentService.align(
                List.of(defect("O1", "100.000", "10")),
                List.of(defect("N1", "100.400", "12"), defect("N2", "100.100", "12")));
        assertEquals(1, pairs.size());
        assertEquals("N2", pairs.get(0).newDefect().defectRef());
    }

    @Test
    void 同一缺陷不会出现在两个匹配对中() {
        List<AlignedPair> pairs = AlignmentService.align(
                List.of(defect("O1", "100.000", "10"), defect("O2", "100.400", "10")),
                List.of(defect("N1", "100.200", "12")));
        assertEquals(1, pairs.size());
        assertEquals("O1", pairs.get(0).oldDefect().defectRef());
        assertEquals("N1", pairs.get(0).newDefect().defectRef());
    }

    @Test
    void 分级阈值口径() {
        assertEquals(Grade.STABLE, Grade.of(-3.0));
        assertEquals(Grade.STABLE, Grade.of(4.999));
        assertEquals(Grade.GROWING, Grade.of(5.0));
        assertEquals(Grade.GROWING, Grade.of(14.999));
        assertEquals(Grade.SEVERE, Grade.of(15.0));
        assertEquals(Grade.SEVERE, Grade.of(40.0));
    }

    @Test
    void 指纹覆盖全部读数且与顺序无关() {
        var base = request("req-1", List.of(defect("O1", "100", "10")),
                List.of(defect("N1", "100.5", "12"), defect("N2", "200", "20")));
        var reordered = request("req-1", List.of(defect("O1", "100", "10")),
                List.of(defect("N2", "200", "20"), defect("N1", "100.5", "12")));
        assertEquals(AlignmentService.fingerprint(base), AlignmentService.fingerprint(reordered));

        var changedDepth = request("req-1", List.of(defect("O1", "100", "11")),
                List.of(defect("N1", "100.5", "12"), defect("N2", "200", "20")));
        assertNotEquals(AlignmentService.fingerprint(base), AlignmentService.fingerprint(changedDepth));

        var changedSegment = new BatchSubmitRequest("req-1", "SEG-B",
                base.oldRun(), base.newRun());
        assertNotEquals(AlignmentService.fingerprint(base), AlignmentService.fingerprint(changedSegment));
    }

    private static BatchSubmitRequest request(String requestId,
                                              List<DefectReading> oldDefects,
                                              List<DefectReading> newDefects) {
        return new BatchSubmitRequest(requestId, "SEG-A",
                new RunDefects("RUN-OLD", LocalDate.of(2023, 1, 1), oldDefects),
                new RunDefects("RUN-NEW", LocalDate.of(2024, 1, 1), newDefects));
    }
}
