package com.example.starter.coldchain.service;

import com.example.starter.coldchain.domain.SegmentStatus;
import com.example.starter.coldchain.entity.Shipment;
import com.example.starter.coldchain.entity.TemperatureReading;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SegmentEvaluatorTest {

    /** 2~8℃，允许连续超限 10 分钟，最大采样间隔 5 分钟。 */
    private Shipment shipment() {
        return new Shipment("s1", 2.0, 8.0, 10, 300, 1,
                LocalDateTime.of(2026, 9, 23, 8, 0));
    }

    private TemperatureReading reading(long seq, LocalDateTime t, double temp) {
        return new TemperatureReading(null, "s1", seq, "DEV-1", t, temp);
    }

    private List<TemperatureReading> readings(long firstSeq, LocalDateTime start,
                                              int intervalSeconds, double... temps) {
        List<TemperatureReading> list = new ArrayList<>();
        for (int i = 0; i < temps.length; i++) {
            list.add(reading(firstSeq + i, start.plusSeconds((long) i * intervalSeconds), temps[i]));
        }
        return list;
    }

    @Test
    void allWithinRangeIncludingEndpointsIsClear() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 23, 8, 0);
        // 端点温度 2.0 与 8.0 视为合格。
        List<TemperatureReading> rs = readings(10, t0, 180, 2.0, 5.0, 8.0, 5.0);
        SegmentStatus status = SegmentEvaluator.evaluate(shipment(), rs, 10, 13);
        assertEquals(SegmentStatus.CLEAR, status);
    }

    @Test
    void excursionExactlyAtLimitIsClear() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 23, 8, 0);
        // 采样间隔均为 5 分钟：9.0 平台 [t0, t0+10min) 恰好 10 分钟（等于上限，合格）。
        List<TemperatureReading> rs = List.of(
                reading(1, t0, 9.0),
                reading(2, t0.plusMinutes(5), 9.0),
                reading(3, t0.plusMinutes(10), 5.0)
        );
        assertEquals(SegmentStatus.CLEAR,
                SegmentEvaluator.evaluate(shipment(), rs, 1, 3));
    }

    @Test
    void excursionBeyondLimitIsExcursion() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 23, 8, 0);
        // 9.0 平台持续 10 分钟 1 秒（间隔 5min/5min/1s，均不超采样间隔）。
        List<TemperatureReading> rs = List.of(
                reading(1, t0, 9.0),
                reading(2, t0.plusMinutes(5), 9.0),
                reading(3, t0.plusMinutes(10), 9.0),
                reading(4, t0.plusMinutes(10).plusSeconds(1), 5.0)
        );
        assertEquals(SegmentStatus.EXCURSION,
                SegmentEvaluator.evaluate(shipment(), rs, 1, 4));
    }

    @Test
    void shortExcursionBrokenByInRangeReadingIsClear() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 23, 8, 0);
        // 两个超限平台各 5 分钟，中间被合格温度打断，连续时长不超过 10 分钟。
        List<TemperatureReading> rs = readings(1, t0, 300, 9.0, 1.0, 5.0, 1.0, 5.0);
        assertEquals(SegmentStatus.CLEAR,
                SegmentEvaluator.evaluate(shipment(), rs, 1, 5));
    }

    @Test
    void missingSequenceIsIncomplete() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 23, 8, 0);
        // 声明 1..3，实际只有 1 和 3。
        List<TemperatureReading> rs = List.of(
                reading(1, t0, 5.0),
                reading(3, t0.plusMinutes(3), 5.0)
        );
        assertEquals(SegmentStatus.INCOMPLETE,
                SegmentEvaluator.evaluate(shipment(), rs, 1, 3));
    }

    @Test
    void gapBeyondMaxIntervalIsIncomplete() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 23, 8, 0);
        // 间隔 5 分 1 秒 > 5 分钟上限。
        List<TemperatureReading> rs = List.of(
                reading(1, t0, 5.0),
                reading(2, t0.plusMinutes(5).plusSeconds(1), 5.0)
        );
        assertEquals(SegmentStatus.INCOMPLETE,
                SegmentEvaluator.evaluate(shipment(), rs, 1, 2));
    }

    @Test
    void gapExactlyAtLimitIsClear() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 23, 8, 0);
        // 间隔恰好 5 分钟（等于上限，合格）。
        List<TemperatureReading> rs = List.of(
                reading(1, t0, 5.0),
                reading(2, t0.plusMinutes(5), 5.0)
        );
        assertEquals(SegmentStatus.CLEAR,
                SegmentEvaluator.evaluate(shipment(), rs, 1, 2));
    }

    @Test
    void emptyReadingsIsIncomplete() {
        assertEquals(SegmentStatus.INCOMPLETE,
                SegmentEvaluator.evaluate(shipment(), List.of(), 1, 1));
    }

    @Test
    void lowerEndpointExcursionUsesSameBoundary() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 23, 8, 0);
        // 1.0 < 下限 2.0，平台持续 11 分钟（采样间隔 5min/5min/1min）→ EXCURSION。
        List<TemperatureReading> rs = List.of(
                reading(1, t0, 1.0),
                reading(2, t0.plusMinutes(5), 1.0),
                reading(3, t0.plusMinutes(10), 1.0),
                reading(4, t0.plusMinutes(11), 5.0)
        );
        assertEquals(SegmentStatus.EXCURSION,
                SegmentEvaluator.evaluate(shipment(), rs, 1, 4));
    }
}
