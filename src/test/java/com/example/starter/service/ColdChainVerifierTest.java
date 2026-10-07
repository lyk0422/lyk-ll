package com.example.starter.service;

import com.example.starter.dto.ReadingInput;
import com.example.starter.dto.SegmentInput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链片段核验器纯单元测试：2/8 度恰边界、5 分钟恰等间隔、缺端点、缺序号、
 * 越界点不被平均温度掩盖、读数不晚于提交时刻等规则逐项验证（不依赖 Spring/H2）。
 */
class ColdChainVerifierTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-09-01T01:00:00Z");

    private static ReadingInput reading(int seq, Instant at, String temp) {
        return new ReadingInput(seq, at, new BigDecimal(temp));
    }

    /** 单片段 [start,end]，两端样本 + 中间每 5 分钟一个样本，温度 5℃。 */
    private static SegmentInput qualifiedSegment(int seq, Instant start, Instant end) {
        List<ReadingInput> readings = new java.util.ArrayList<>();
        int r = 1;
        for (Instant at = start; !at.isAfter(end); at = at.plusSeconds(300)) {
            readings.add(reading(r++, at, "5.0"));
        }
        return new SegmentInput(seq, start, end, readings);
    }

    private static List<String> codes(List<ColdChainVerifier.Gap> gaps) {
        return gaps.stream().map(ColdChainVerifier.Gap::code).toList();
    }

    @Test
    void qualifiedSingleSegmentPasses() {
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(
                List.of(qualifiedSegment(1, T0, T0.plusSeconds(600))), SUBMITTED_AT);
        assertTrue(gaps.isEmpty(), "完整合格证据不应有缺口: " + gaps);
    }

    @Test
    void temperatureExactly2And8DegreesPass() {
        SegmentInput segment = new SegmentInput(1, T0, T0.plusSeconds(600), List.of(
                reading(1, T0, "2.0"),
                reading(2, T0.plusSeconds(300), "8.0"),
                reading(3, T0.plusSeconds(600), "2")));
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(List.of(segment), SUBMITTED_AT);
        assertTrue(gaps.isEmpty(), "恰 2℃ 与恰 8℃ 边界应判合格: " + gaps);
    }

    @Test
    void temperatureBelow2OrAbove8Fails() {
        SegmentInput segment = new SegmentInput(1, T0, T0.plusSeconds(600), List.of(
                reading(1, T0, "1.99"),
                reading(2, T0.plusSeconds(300), "8.01"),
                reading(3, T0.plusSeconds(600), "5.0")));
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(List.of(segment), SUBMITTED_AT);
        assertEquals(2, gaps.stream()
                .filter(g -> g.code().equals(ColdChainVerifier.TEMPERATURE_OUT_OF_RANGE)).count());
    }

    @Test
    void outOfRangePointCannotBeMaskedByAverage() {
        // 三点温度 2.0 / 8.5 / 2.0，平均约 4.17℃ 看似正常，但 8.5℃ 越界必须判不合格。
        SegmentInput segment = new SegmentInput(1, T0, T0.plusSeconds(600), List.of(
                reading(1, T0, "2.0"),
                reading(2, T0.plusSeconds(300), "8.5"),
                reading(3, T0.plusSeconds(600), "2.0")));
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(List.of(segment), SUBMITTED_AT);
        assertEquals(List.of(ColdChainVerifier.TEMPERATURE_OUT_OF_RANGE), codes(gaps));
        ColdChainVerifier.Gap gap = gaps.get(0);
        assertEquals("8.5", gap.value(), "越界点须记录实际温度值");
        assertEquals(T0.plusSeconds(300), gap.at(), "越界点须记录精确时刻");
    }

    @Test
    void sampleIntervalExactly5MinutesPasses() {
        SegmentInput segment = new SegmentInput(1, T0, T0.plusSeconds(600), List.of(
                reading(1, T0, "5.0"),
                reading(2, T0.plusSeconds(300), "5.0"),
                reading(3, T0.plusSeconds(600), "5.0")));
        assertTrue(ColdChainVerifier.verify(List.of(segment), SUBMITTED_AT).isEmpty(),
                "相邻样本恰 5 分钟应判合格");
    }

    @Test
    void sampleInterval5MinutesPlus1SecondFails() {
        Instant end = T0.plusSeconds(601);
        SegmentInput segment = new SegmentInput(1, T0, end, List.of(
                reading(1, T0, "5.0"),
                reading(2, end, "5.0")));
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(List.of(segment), SUBMITTED_AT);
        assertEquals(List.of(ColdChainVerifier.SAMPLE_INTERVAL_EXCEEDED), codes(gaps));
        assertEquals("601", gaps.get(0).value(), "间隔超限须记录实际秒数");
    }

    @Test
    void missingEndSampleIsGapNotZeroDegree() {
        // 缺终点样本：不得按零度或正常值补齐，必须记缺口。
        SegmentInput segment = new SegmentInput(1, T0, T0.plusSeconds(600), List.of(
                reading(1, T0, "5.0"),
                reading(2, T0.plusSeconds(300), "5.0")));
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(List.of(segment), SUBMITTED_AT);
        assertEquals(List.of(ColdChainVerifier.MISSING_END_SAMPLE), codes(gaps));
        assertEquals(T0.plusSeconds(600), gaps.get(0).at(), "缺端点缺口须指向缺失时刻");
    }

    @Test
    void missingStartSampleFails() {
        SegmentInput segment = new SegmentInput(1, T0, T0.plusSeconds(600), List.of(
                reading(1, T0.plusSeconds(300), "5.0"),
                reading(2, T0.plusSeconds(600), "5.0")));
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(List.of(segment), SUBMITTED_AT);
        assertTrue(codes(gaps).contains(ColdChainVerifier.MISSING_START_SAMPLE));
    }

    @Test
    void emptyReadingsMissBothEndpoints() {
        SegmentInput segment = new SegmentInput(1, T0, T0.plusSeconds(600), List.of());
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(List.of(segment), SUBMITTED_AT);
        assertTrue(codes(gaps).contains(ColdChainVerifier.MISSING_START_SAMPLE));
        assertTrue(codes(gaps).contains(ColdChainVerifier.MISSING_END_SAMPLE));
    }

    @Test
    void nonContiguousSegmentsFail() {
        SegmentInput first = qualifiedSegment(1, T0, T0.plusSeconds(600));
        // 第二片起点与前片终点错开 1 秒：未完全相接。
        SegmentInput second = qualifiedSegment(2, T0.plusSeconds(601), T0.plusSeconds(1201));
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(
                List.of(first, second), SUBMITTED_AT);
        assertTrue(codes(gaps).contains(ColdChainVerifier.SEGMENT_NOT_CONTIGUOUS));
    }

    @Test
    void touchingSegmentsPass() {
        SegmentInput first = qualifiedSegment(1, T0, T0.plusSeconds(600));
        SegmentInput second = qualifiedSegment(2, T0.plusSeconds(600), T0.plusSeconds(1200));
        assertTrue(ColdChainVerifier.verify(List.of(first, second), SUBMITTED_AT).isEmpty(),
                "端点完全相接的两片段应判合格");
    }

    @Test
    void zeroLengthSegmentFails() {
        SegmentInput segment = new SegmentInput(1, T0, T0, List.of(
                reading(1, T0, "5.0")));
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(List.of(segment), SUBMITTED_AT);
        assertTrue(codes(gaps).contains(ColdChainVerifier.SEGMENT_NOT_POSITIVE));
    }

    @Test
    void segmentSeqGapFails() {
        SegmentInput segment = qualifiedSegment(2, T0, T0.plusSeconds(600));
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(List.of(segment), SUBMITTED_AT);
        assertTrue(codes(gaps).contains(ColdChainVerifier.SEGMENT_SEQ_GAP));
    }

    @Test
    void readingSeqGapFails() {
        SegmentInput segment = new SegmentInput(1, T0, T0.plusSeconds(600), List.of(
                reading(1, T0, "5.0"),
                reading(3, T0.plusSeconds(300), "5.0"),
                reading(4, T0.plusSeconds(600), "5.0")));
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(List.of(segment), SUBMITTED_AT);
        assertTrue(codes(gaps).contains(ColdChainVerifier.READING_SEQ_GAP), "缺读数序号不得通过");
    }

    @Test
    void readingAfterSubmissionFails() {
        Instant submittedAt = T0.plusSeconds(300);
        SegmentInput segment = new SegmentInput(1, T0, T0.plusSeconds(600), List.of(
                reading(1, T0, "5.0"),
                reading(2, T0.plusSeconds(300), "5.0"),
                reading(3, T0.plusSeconds(600), "5.0")));
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(List.of(segment), submittedAt);
        assertTrue(codes(gaps).contains(ColdChainVerifier.READING_AFTER_SUBMISSION));
    }

    @Test
    void readingOutsideSegmentIntervalFails() {
        SegmentInput segment = new SegmentInput(1, T0, T0.plusSeconds(600), List.of(
                reading(1, T0, "5.0"),
                reading(2, T0.minusSeconds(60), "5.0"),
                reading(3, T0.plusSeconds(600), "5.0")));
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(List.of(segment), SUBMITTED_AT);
        assertTrue(codes(gaps).contains(ColdChainVerifier.READING_OUT_OF_SEGMENT));
    }

    @Test
    void nonIncreasingReadingTimeFails() {
        SegmentInput segment = new SegmentInput(1, T0, T0.plusSeconds(600), List.of(
                reading(1, T0, "5.0"),
                reading(2, T0, "5.0"),
                reading(3, T0.plusSeconds(600), "5.0")));
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(List.of(segment), SUBMITTED_AT);
        assertTrue(codes(gaps).contains(ColdChainVerifier.READING_TIME_NOT_INCREASING));
    }
}
