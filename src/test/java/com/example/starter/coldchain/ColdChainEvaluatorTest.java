package com.example.starter.coldchain;

import com.example.starter.domain.ColdChainReading;
import com.example.starter.domain.ColdChainSegment;
import com.example.starter.dto.ColdChainSegmentInput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链证据核验器纯单元测试：2/8 度恰边界、5 分钟恰等间隔、缺端点/缺片/缺序号、
 * 越界点逐点判定（不按平均温度掩盖）、片段结构（1-5 个、正长度、端点相接）。
 */
class ColdChainEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant SUBMIT_AT = Instant.parse("2026-09-02T00:00:00Z");

    private static ColdChainSegment segment(int seq, String start, String end) {
        ColdChainSegment segment = new ColdChainSegment();
        segment.setSegmentSeq(seq);
        segment.setStartUtc(Instant.parse(start));
        segment.setEndUtc(Instant.parse(end));
        return segment;
    }

    private static ColdChainReading reading(int segmentSeq, int seq, String at, String temperature) {
        ColdChainReading reading = new ColdChainReading();
        reading.setSegmentSeq(segmentSeq);
        reading.setReadingSeq(seq);
        reading.setReadAt(Instant.parse(at));
        reading.setTemperature(new BigDecimal(temperature));
        return reading;
    }

    /** 单片段 [00:00, 00:10)，两端样本 + 中间恰 5 分钟一个样本的合格读数。 */
    private static List<ColdChainReading> qualifiedReadings(String low, String high) {
        return List.of(
                reading(1, 1, "2026-09-01T00:00:00Z", low),
                reading(1, 2, "2026-09-01T00:05:00Z", "5.0"),
                reading(1, 3, "2026-09-01T00:10:00Z", high));
    }

    private static List<ColdChainSegment> oneSegment() {
        return List.of(segment(1, "2026-09-01T00:00:00Z", "2026-09-01T00:10:00Z"));
    }

    @Test
    void boundaryTemperatures2And8ExactlyPass() {
        ColdChainEvaluator.Evaluation evaluation = ColdChainEvaluator.evaluate(
                oneSegment(), Map.of(1, qualifiedReadings("2", "8")), SUBMIT_AT);
        assertTrue(evaluation.qualified(),
                "恰 2 度与恰 8 度边界合格: " + evaluation.gaps() + evaluation.violations());
    }

    @Test
    void outOfRangePointFailsEvenIfAverageIsFine() {
        // 平均温度约 5 度完全正常，但任一点越界即不通过。
        ColdChainEvaluator.Evaluation low = ColdChainEvaluator.evaluate(
                oneSegment(), Map.of(1, qualifiedReadings("1.9", "8")), SUBMIT_AT);
        assertFalse(low.qualified());
        assertEquals(1, low.violations().size());
        assertEquals(1, low.violations().get(0).readingSeq());
        assertEquals(0, new BigDecimal("1.9").compareTo(low.violations().get(0).temperature()));

        ColdChainEvaluator.Evaluation high = ColdChainEvaluator.evaluate(
                oneSegment(), Map.of(1, qualifiedReadings("2", "8.1")), SUBMIT_AT);
        assertFalse(high.qualified());
        assertEquals(1, high.violations().size());
        assertEquals(3, high.violations().get(0).readingSeq());
    }

    @Test
    void exactlyFiveMinutesIntervalPassesAndLongerFails() {
        // 恰 5 分钟间隔：合格（qualifiedReadings 即 5 分钟间隔）。
        assertTrue(ColdChainEvaluator.evaluate(
                oneSegment(), Map.of(1, qualifiedReadings("4", "6")), SUBMIT_AT).qualified());

        // 5 分 1 秒：超间隔缺口。
        List<ColdChainReading> tooLong = List.of(
                reading(1, 1, "2026-09-01T00:00:00Z", "4"),
                reading(1, 2, "2026-09-01T00:05:01Z", "5"),
                reading(1, 3, "2026-09-01T00:10:00Z", "6"));
        ColdChainEvaluator.Evaluation evaluation = ColdChainEvaluator.evaluate(
                oneSegment(), Map.of(1, tooLong), SUBMIT_AT);
        assertFalse(evaluation.qualified());
        assertTrue(evaluation.gaps().stream().anyMatch(g -> g.contains("间隔超过 5 分钟")),
                "5 分 1 秒须判超间隔: " + evaluation.gaps());
    }

    @Test
    void missingEndpointSamplesAreGapsNotZeroDegrees() {
        // 缺终点样本：最后一个样本在 00:05 而非 00:10。
        List<ColdChainReading> missingEnd = List.of(
                reading(1, 1, "2026-09-01T00:00:00Z", "4"),
                reading(1, 2, "2026-09-01T00:05:00Z", "5"));
        ColdChainEvaluator.Evaluation endGaps = ColdChainEvaluator.evaluate(
                oneSegment(), Map.of(1, missingEnd), SUBMIT_AT);
        assertFalse(endGaps.qualified());
        assertTrue(endGaps.gaps().stream().anyMatch(g -> g.contains("缺少终点")),
                "缺终点样本须记缺口: " + endGaps.gaps());
        assertTrue(endGaps.violations().isEmpty(), "缺样本不是越界点");

        // 缺起点样本。
        List<ColdChainReading> missingStart = List.of(
                reading(1, 1, "2026-09-01T00:05:00Z", "4"),
                reading(1, 2, "2026-09-01T00:10:00Z", "5"));
        ColdChainEvaluator.Evaluation startGaps = ColdChainEvaluator.evaluate(
                oneSegment(), Map.of(1, missingStart), SUBMIT_AT);
        assertFalse(startGaps.qualified());
        assertTrue(startGaps.gaps().stream().anyMatch(g -> g.contains("缺少起点")));
    }

    @Test
    void missingWholeSegmentIsGap() {
        List<ColdChainSegment> twoSegments = List.of(
                segment(1, "2026-09-01T00:00:00Z", "2026-09-01T00:10:00Z"),
                segment(2, "2026-09-01T00:10:00Z", "2026-09-01T00:20:00Z"));
        // 只提交片段 1 的读数，片段 2 缺片。
        ColdChainEvaluator.Evaluation evaluation = ColdChainEvaluator.evaluate(
                twoSegments, Map.of(1, qualifiedReadings("4", "6")), SUBMIT_AT);
        assertFalse(evaluation.qualified());
        assertTrue(evaluation.gaps().stream().anyMatch(g -> g.contains("片段 2 缺少温度读数")),
                "缺片须记缺口: " + evaluation.gaps());
    }

    @Test
    void duplicateSeqAndNonIncreasingTimesAreGaps() {
        List<ColdChainReading> duplicated = List.of(
                reading(1, 1, "2026-09-01T00:00:00Z", "4"),
                reading(1, 1, "2026-09-01T00:05:00Z", "5"),
                reading(1, 2, "2026-09-01T00:10:00Z", "6"));
        ColdChainEvaluator.Evaluation dup = ColdChainEvaluator.evaluate(
                oneSegment(), Map.of(1, duplicated), SUBMIT_AT);
        assertFalse(dup.qualified());
        assertTrue(dup.gaps().stream().anyMatch(g -> g.contains("序号重复")));

        List<ColdChainReading> backwards = List.of(
                reading(1, 1, "2026-09-01T00:00:00Z", "4"),
                reading(1, 2, "2026-09-01T00:03:00Z", "5"),
                reading(1, 3, "2026-09-01T00:02:00Z", "6"));
        ColdChainEvaluator.Evaluation back = ColdChainEvaluator.evaluate(
                oneSegment(), Map.of(1, backwards), SUBMIT_AT);
        assertFalse(back.qualified());
        assertTrue(back.gaps().stream().anyMatch(g -> g.contains("未随序号递增")));
    }

    @Test
    void readingOutsideSegmentOrAfterSubmissionIsViolation() {
        // 读数时刻超出片段闭区间。
        List<ColdChainReading> outside = List.of(
                reading(1, 1, "2026-09-01T00:00:00Z", "4"),
                reading(1, 2, "2026-09-01T00:10:01Z", "5"),
                reading(1, 3, "2026-09-01T00:10:00Z", "6"));
        ColdChainEvaluator.Evaluation out = ColdChainEvaluator.evaluate(
                oneSegment(), Map.of(1, outside), SUBMIT_AT);
        assertFalse(out.qualified());
        assertTrue(out.violations().stream().anyMatch(v -> v.reason().contains("超出片段闭区间")));

        // 读数晚于提交时刻（提交时刻 00:30，读数在 01:00）。
        List<ColdChainSegment> lateSegments = List.of(
                segment(1, "2026-09-01T00:50:00Z", "2026-09-01T01:00:00Z"));
        List<ColdChainReading> future = List.of(
                reading(1, 1, "2026-09-01T00:50:00Z", "4"),
                reading(1, 2, "2026-09-01T01:00:00Z", "5"));
        ColdChainEvaluator.Evaluation futureEval = ColdChainEvaluator.evaluate(
                lateSegments, Map.of(1, future), Instant.parse("2026-09-01T00:30:00Z"));
        assertFalse(futureEval.qualified());
        assertTrue(futureEval.violations().stream().anyMatch(v -> v.reason().contains("晚于提交时刻")));
    }

    @Test
    void segmentStructureValidation() {
        // 合法：两个相接片段。
        assertTrue(ColdChainEvaluator.validateSegmentStructure(List.of(
                new ColdChainSegmentInput(1, T0, T0.plusSeconds(600)),
                new ColdChainSegmentInput(2, T0.plusSeconds(600), T0.plusSeconds(1200)))).isEmpty());

        // 0 个与 6 个片段。
        assertFalse(ColdChainEvaluator.validateSegmentStructure(List.of()).isEmpty());
        List<ColdChainSegmentInput> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(new ColdChainSegmentInput(i + 1,
                    T0.plusSeconds(i * 600L), T0.plusSeconds((i + 1) * 600L)));
        }
        assertFalse(ColdChainEvaluator.validateSegmentStructure(six).isEmpty());

        // 零长度/负长度。
        assertFalse(ColdChainEvaluator.validateSegmentStructure(List.of(
                new ColdChainSegmentInput(1, T0, T0))).isEmpty());
        assertFalse(ColdChainEvaluator.validateSegmentStructure(List.of(
                new ColdChainSegmentInput(1, T0.plusSeconds(600), T0))).isEmpty());

        // 相邻端点未相接。
        assertFalse(ColdChainEvaluator.validateSegmentStructure(List.of(
                new ColdChainSegmentInput(1, T0, T0.plusSeconds(600)),
                new ColdChainSegmentInput(2, T0.plusSeconds(601), T0.plusSeconds(1200)))).isEmpty());

        // 序号不从 1 连续递增。
        assertFalse(ColdChainEvaluator.validateSegmentStructure(List.of(
                new ColdChainSegmentInput(2, T0, T0.plusSeconds(600)))).isEmpty());
    }
}
