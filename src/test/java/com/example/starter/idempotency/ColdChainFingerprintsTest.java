package com.example.starter.idempotency;

import com.example.starter.dto.ColdChainSubmitRequest;
import com.example.starter.dto.ReadingInput;
import com.example.starter.dto.SegmentInput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 冷链证据提交结构化指纹单元测试：
 * 同键同参一致；版本、提交精确时刻、任一片段起止、任一读数时刻或温度变化都必须区分；
 * 温度 6.5 与 6.50 归一化后指纹一致。
 */
class ColdChainFingerprintsTest {

    private static final Instant START = Instant.parse("2026-08-31T10:00:00Z");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-08-31T10:30:00Z");

    private static List<SegmentInput> twoSegments() {
        return List.of(
                new SegmentInput(1, START, START.plusSeconds(600), List.of(
                        new ReadingInput(1, START, new BigDecimal("5.0")),
                        new ReadingInput(2, START.plusSeconds(300), new BigDecimal("6.5")),
                        new ReadingInput(3, START.plusSeconds(600), new BigDecimal("5.0")))),
                new SegmentInput(2, START.plusSeconds(600), START.plusSeconds(1200), List.of(
                        new ReadingInput(1, START.plusSeconds(600), new BigDecimal("5.0")),
                        new ReadingInput(2, START.plusSeconds(900), new BigDecimal("5.0")),
                        new ReadingInput(3, START.plusSeconds(1200), new BigDecimal("5.0")))));
    }

    private static ColdChainSubmitRequest request(String requestId, int expectedVersion,
                                                  Instant submittedAt, List<SegmentInput> segments) {
        return new ColdChainSubmitRequest(requestId, expectedVersion, submittedAt, segments);
    }

    @Test
    void sameKeySameParametersHaveSameFingerprint() {
        String a = Fingerprints.coldchainSubmit("B-1",
                request("cc-1", 0, SUBMITTED_AT, twoSegments()));
        String b = Fingerprints.coldchainSubmit("B-1",
                request("cc-1", 0, SUBMITTED_AT, twoSegments()));
        assertEquals(a, b);
    }

    @Test
    void temperatureScaleIsNormalized() {
        List<SegmentInput> scaled = List.of(
                new SegmentInput(1, START, START.plusSeconds(600), List.of(
                        new ReadingInput(1, START, new BigDecimal("5.0")),
                        new ReadingInput(2, START.plusSeconds(300), new BigDecimal("6.50")),
                        new ReadingInput(3, START.plusSeconds(600), new BigDecimal("5.0")))),
                twoSegments().get(1));
        assertEquals(
                Fingerprints.coldchainSubmit("B-1", request("cc-1", 0, SUBMITTED_AT, twoSegments())),
                Fingerprints.coldchainSubmit("B-1", request("cc-1", 0, SUBMITTED_AT, scaled)),
                "6.5 与 6.50 语义相同，指纹须一致");
    }

    @Test
    void versionMomentSegmentOrReadingChangeMustDiffer() {
        String base = Fingerprints.coldchainSubmit("B-1",
                request("cc-1", 0, SUBMITTED_AT, twoSegments()));

        assertNotEquals(base, Fingerprints.coldchainSubmit("B-1",
                        request("cc-1", 1, SUBMITTED_AT, twoSegments())),
                "期望版本变化必须区分");
        assertNotEquals(base, Fingerprints.coldchainSubmit("B-1",
                        request("cc-1", 0, SUBMITTED_AT.plusSeconds(1), twoSegments())),
                "提交精确时刻变化必须区分");
        assertNotEquals(base, Fingerprints.coldchainSubmit("B-2",
                        request("cc-1", 0, SUBMITTED_AT, twoSegments())),
                "批号变化必须区分");

        // 片段终点平移 1 秒。
        List<SegmentInput> shiftedEnd = List.of(
                new SegmentInput(1, START, START.plusSeconds(601), List.of(
                        new ReadingInput(1, START, new BigDecimal("5.0")),
                        new ReadingInput(2, START.plusSeconds(300), new BigDecimal("6.5")),
                        new ReadingInput(3, START.plusSeconds(601), new BigDecimal("5.0")))),
                twoSegments().get(1));
        assertNotEquals(base, Fingerprints.coldchainSubmit("B-1",
                        request("cc-1", 0, SUBMITTED_AT, shiftedEnd)),
                "片段起止变化必须区分");

        // 单个读数温度变化。
        List<SegmentInput> warmer = List.of(
                new SegmentInput(1, START, START.plusSeconds(600), List.of(
                        new ReadingInput(1, START, new BigDecimal("5.0")),
                        new ReadingInput(2, START.plusSeconds(300), new BigDecimal("6.6")),
                        new ReadingInput(3, START.plusSeconds(600), new BigDecimal("5.0")))),
                twoSegments().get(1));
        assertNotEquals(base, Fingerprints.coldchainSubmit("B-1",
                        request("cc-1", 0, SUBMITTED_AT, warmer)),
                "任一读数温度变化必须区分");

        // 单个读数时刻变化。
        List<SegmentInput> retimed = List.of(
                new SegmentInput(1, START, START.plusSeconds(600), List.of(
                        new ReadingInput(1, START, new BigDecimal("5.0")),
                        new ReadingInput(2, START.plusSeconds(301), new BigDecimal("6.5")),
                        new ReadingInput(3, START.plusSeconds(600), new BigDecimal("5.0")))),
                twoSegments().get(1));
        assertNotEquals(base, Fingerprints.coldchainSubmit("B-1",
                        request("cc-1", 0, SUBMITTED_AT, retimed)),
                "任一读数精确时刻变化必须区分");
    }
}
