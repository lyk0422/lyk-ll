package com.example.starter.idempotency;

import com.example.starter.dto.ColdChainCorrectRequest;
import com.example.starter.dto.ColdChainReadingInput;
import com.example.starter.dto.ColdChainReadingsSubmitRequest;
import com.example.starter.dto.ColdChainSegmentInput;
import com.example.starter.dto.ColdChainSegmentReadingsInput;
import com.example.starter.dto.ColdChainSegmentRegisterRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 冷链幂等指纹单元测试：完整结构化指纹覆盖全片段、读数、版本与精确时刻；
 * 同参一致、异参不同，温度按数值归一化（2.0 与 2.00 相同）。
 */
class ColdChainFingerprintsTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T00:10:00Z");
    private static final Instant T2 = Instant.parse("2026-09-01T00:20:00Z");
    private static final Instant AT = Instant.parse("2026-09-01T03:00:00Z");

    private static List<ColdChainSegmentInput> segments() {
        return List.of(
                new ColdChainSegmentInput(1, T0, T1),
                new ColdChainSegmentInput(2, T1, T2));
    }

    private static List<ColdChainSegmentReadingsInput> readings(String temperature) {
        return List.of(
                new ColdChainSegmentReadingsInput(1, List.of(
                        new ColdChainReadingInput(1, T0, new BigDecimal(temperature)),
                        new ColdChainReadingInput(2, T0.plusSeconds(300), new BigDecimal("5")),
                        new ColdChainReadingInput(3, T1, new BigDecimal("6")))),
                new ColdChainSegmentReadingsInput(2, List.of(
                        new ColdChainReadingInput(1, T1, new BigDecimal("4")),
                        new ColdChainReadingInput(2, T1.plusSeconds(300), new BigDecimal("5")),
                        new ColdChainReadingInput(3, T2, new BigDecimal("6")))));
    }

    @Test
    void sameParamsSameFingerprint() {
        var a = new ColdChainSegmentRegisterRequest("r1", "B-1", segments(), AT);
        var b = new ColdChainSegmentRegisterRequest("r1", "B-1", segments(), AT);
        assertEquals(Fingerprints.coldChainRegister(a), Fingerprints.coldChainRegister(b));
    }

    @Test
    void registerFingerprintCoversAllSegmentsAndInstants() {
        var base = new ColdChainSegmentRegisterRequest("r1", "B-1", segments(), AT);
        // 任一片段端点变化 → 指纹不同。
        var shifted = new ColdChainSegmentRegisterRequest("r1", "B-1", List.of(
                new ColdChainSegmentInput(1, T0, T1),
                new ColdChainSegmentInput(2, T1, T2.plusSeconds(1))), AT);
        assertNotEquals(Fingerprints.coldChainRegister(base), Fingerprints.coldChainRegister(shifted));
        // 登记时刻变化 → 指纹不同。
        var otherAt = new ColdChainSegmentRegisterRequest("r1", "B-1", segments(), AT.plusSeconds(1));
        assertNotEquals(Fingerprints.coldChainRegister(base), Fingerprints.coldChainRegister(otherAt));
        // 片段数量变化 → 指纹不同。
        var oneSegment = new ColdChainSegmentRegisterRequest("r1", "B-1",
                List.of(new ColdChainSegmentInput(1, T0, T1)), AT);
        assertNotEquals(Fingerprints.coldChainRegister(base), Fingerprints.coldChainRegister(oneSegment));
    }

    @Test
    void readingsFingerprintCoversVersionReadingsAndSubmissionTime() {
        var base = new ColdChainReadingsSubmitRequest("r1", "B-1", 1, readings("2.0"), AT);
        assertEquals(Fingerprints.coldChainReadings(base),
                Fingerprints.coldChainReadings(
                        new ColdChainReadingsSubmitRequest("r1", "B-1", 1, readings("2.0"), AT)),
                "同参指纹一致");
        // 温度数值等价（2.0 与 2.00）→ 指纹一致。
        assertEquals(Fingerprints.coldChainReadings(base),
                Fingerprints.coldChainReadings(
                        new ColdChainReadingsSubmitRequest("r1", "B-1", 1, readings("2.00"), AT)),
                "温度按数值归一化");
        // 温度实际不同 → 指纹不同。
        assertNotEquals(Fingerprints.coldChainReadings(base),
                Fingerprints.coldChainReadings(
                        new ColdChainReadingsSubmitRequest("r1", "B-1", 1, readings("2.01"), AT)));
        // 版本不同 → 指纹不同。
        assertNotEquals(Fingerprints.coldChainReadings(base),
                Fingerprints.coldChainReadings(
                        new ColdChainReadingsSubmitRequest("r1", "B-1", 2, readings("2.0"), AT)));
        // 提交时刻不同 → 指纹不同。
        assertNotEquals(Fingerprints.coldChainReadings(base),
                Fingerprints.coldChainReadings(
                        new ColdChainReadingsSubmitRequest("r1", "B-1", 1, readings("2.0"),
                                AT.plusSeconds(1))));
    }

    @Test
    void correctFingerprintCoversVersionAndSegments() {
        var base = new ColdChainCorrectRequest("r1", "B-1", 1, segments(), AT);
        assertNotEquals(Fingerprints.coldChainCorrect(base),
                Fingerprints.coldChainCorrect(new ColdChainCorrectRequest("r1", "B-1", 2, segments(), AT)),
                "期望版本纳入指纹");
        assertNotEquals(Fingerprints.coldChainCorrect(base),
                Fingerprints.coldChainCorrect(new ColdChainCorrectRequest("r1", "B-1", 1,
                        List.of(new ColdChainSegmentInput(1, T0, T2)), AT)),
                "更正后片段纳入指纹");
        // 不同操作类型即使字段相近也不碰撞。
        assertNotEquals(Fingerprints.coldChainCorrect(base),
                Fingerprints.coldChainRegister(
                        new ColdChainSegmentRegisterRequest("r1", "B-1", segments(), AT)));
    }
}
