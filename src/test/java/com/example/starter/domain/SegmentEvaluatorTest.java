package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SegmentEvaluator} 纯单元测试：乱序读数、阈值端点、连续超限、缺号、采样间隔超限。
 */
class SegmentEvaluatorTest {

    private static final long MINUTE = 60_000L;
    private static final long T0 = 1_700_000_000_000L;

    private static Shipment shipment(int maxExcursionMinutes, int maxSampleIntervalMinutes) {
        return new Shipment("S1", new BigDecimal("2.0"), new BigDecimal("8.0"),
                maxExcursionMinutes, maxSampleIntervalMinutes, 0, T0);
    }

    private static TemperatureReading reading(long seq, long offsetMinutes, String temp) {
        return new TemperatureReading("S1", seq, T0 + offsetMinutes * MINUTE,
                new BigDecimal(temp), T0);
    }

    @Test
    void clearWhenAllReadingsInRange() {
        var readings = List.of(
                reading(1, 0, "4.0"),
                reading(2, 5, "5.0"),
                reading(3, 10, "6.0"));
        assertEquals(SegmentStatus.CLEAR,
                SegmentEvaluator.evaluate(shipment(10, 15), 1, 3, readings));
    }

    @Test
    void outOfOrderArrivalIsSortedBySequence() {
        // 乱序到达：按序号排序后判定，不影响结果
        var readings = List.of(
                reading(3, 10, "6.0"),
                reading(1, 0, "4.0"),
                reading(2, 5, "5.0"));
        assertEquals(SegmentStatus.CLEAR,
                SegmentEvaluator.evaluate(shipment(10, 15), 1, 3, readings));
    }

    @Test
    void thresholdEndpointsAreCompliant() {
        // 端点等于阈值视为合格
        var readings = List.of(
                reading(1, 0, "2.0"),
                reading(2, 30, "8.0"),
                reading(3, 60, "2.0"));
        assertEquals(SegmentStatus.CLEAR,
                SegmentEvaluator.evaluate(shipment(10, 90), 1, 3, readings));
    }

    @Test
    void excursionWhenContinuousBreachExceedsLimit() {
        // 4.0 -> 9.0(超限) 持续 20 分钟 -> 6.0：连续超限 20 分钟 > 允许 10 分钟
        var readings = List.of(
                reading(1, 0, "4.0"),
                reading(2, 5, "9.0"),
                reading(3, 25, "6.0"));
        assertEquals(SegmentStatus.EXCURSION,
                SegmentEvaluator.evaluate(shipment(10, 60), 1, 3, readings));
    }

    @Test
    void noExcursionWhenContinuousBreachWithinLimit() {
        // 超限持续恰好 10 分钟，不大于允许 10 分钟
        var readings = List.of(
                reading(1, 0, "4.0"),
                reading(2, 5, "9.0"),
                reading(3, 15, "6.0"));
        assertEquals(SegmentStatus.CLEAR,
                SegmentEvaluator.evaluate(shipment(10, 60), 1, 3, readings));
    }

    @Test
    void trailingBreachDoesNotAccumulateBeyondLastReading() {
        // 末尾读数超限但没有后续读数，持续时长未知，按现有证据不判超限
        var readings = List.of(
                reading(1, 0, "4.0"),
                reading(2, 5, "9.0"));
        assertEquals(SegmentStatus.CLEAR,
                SegmentEvaluator.evaluate(shipment(10, 60), 1, 2, readings));
    }

    @Test
    void incompleteWhenSequenceMissing() {
        // 缺号：范围 [1,3] 只有 1 和 3
        var readings = List.of(
                reading(1, 0, "4.0"),
                reading(3, 10, "5.0"));
        assertEquals(SegmentStatus.INCOMPLETE,
                SegmentEvaluator.evaluate(shipment(10, 60), 1, 3, readings));
    }

    @Test
    void incompleteWhenRangeHasNoReadings() {
        assertEquals(SegmentStatus.INCOMPLETE,
                SegmentEvaluator.evaluate(shipment(10, 60), 1, 3, List.of()));
    }

    @Test
    void incompleteWhenSampleIntervalExceeded() {
        // 相邻间隔 20 分钟 > 允许 15 分钟
        var readings = List.of(
                reading(1, 0, "4.0"),
                reading(2, 20, "5.0"));
        assertEquals(SegmentStatus.INCOMPLETE,
                SegmentEvaluator.evaluate(shipment(10, 15), 1, 2, readings));
    }

    @Test
    void clearWhenSampleIntervalExactlyAtLimit() {
        // 相邻间隔恰好等于允许上限：不超限
        var readings = List.of(
                reading(1, 0, "4.0"),
                reading(2, 15, "5.0"));
        assertEquals(SegmentStatus.CLEAR,
                SegmentEvaluator.evaluate(shipment(10, 15), 1, 2, readings));
    }

    @Test
    void singleReadingSegmentIsClear() {
        var readings = List.of(reading(7, 0, "5.0"));
        assertEquals(SegmentStatus.CLEAR,
                SegmentEvaluator.evaluate(shipment(10, 15), 7, 7, readings));
    }
}
