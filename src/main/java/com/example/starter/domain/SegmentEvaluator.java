package com.example.starter.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * 分段温度证据判定：缺号或采样间隔超限为 {@link SegmentStatus#INCOMPLETE}；
 * 否则存在连续超限严格大于允许分钟数为 {@link SegmentStatus#EXCURSION}；其余为 {@link SegmentStatus#CLEAR}。
 *
 * <p>端点等于温度阈值视为合格；沿用规则：相邻读数之间整段沿用前一读数。
 * 判定输入为段序号闭区间 [firstSeq, lastSeq] 内实际存在的读数（可乱序到达）。</p>
 */
public final class SegmentEvaluator {

    private static final long MILLIS_PER_MINUTE = 60_000L;

    private SegmentEvaluator() {
    }

    /**
     * @param shipment 货件阈值配置
     * @param firstSeq 段首序号（含）
     * @param lastSeq  段末序号（含）
     * @param readings 段范围内实际存在的读数（无需保证有序）
     * @return 判定状态
     */
    public static SegmentStatus evaluate(Shipment shipment, long firstSeq, long lastSeq,
                                         List<TemperatureReading> readings) {
        if (firstSeq > lastSeq) {
            return SegmentStatus.INCOMPLETE;
        }
        long expected = lastSeq - firstSeq + 1;
        if (readings.size() != expected) {
            return SegmentStatus.INCOMPLETE;
        }
        List<TemperatureReading> ordered = readings.stream()
                .sorted(Comparator.comparingLong(TemperatureReading::sequenceNo))
                .toList();
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).sequenceNo() != firstSeq + i) {
                return SegmentStatus.INCOMPLETE;
            }
        }
        long maxGapMillis = (long) shipment.maxSampleIntervalMinutes() * MILLIS_PER_MINUTE;
        for (int i = 1; i < ordered.size(); i++) {
            long gap = ordered.get(i).sampledAt() - ordered.get(i - 1).sampledAt();
            if (gap > maxGapMillis) {
                return SegmentStatus.INCOMPLETE;
            }
        }
        return hasExcursion(shipment, ordered) ? SegmentStatus.EXCURSION : SegmentStatus.CLEAR;
    }

    /**
     * 是否存在连续超限严格大于最大允许连续超限分钟数。
     * 相邻读数之间整段沿用前一读数；端点等于阈值视为合格。
     */
    private static boolean hasExcursion(Shipment shipment, List<TemperatureReading> ordered) {
        BigDecimal min = shipment.minTempC();
        BigDecimal max = shipment.maxTempC();
        long maxExcursionMillis = (long) shipment.maxExcursionMinutes() * MILLIS_PER_MINUTE;
        long runStart = -1;
        for (int i = 0; i < ordered.size(); i++) {
            BigDecimal t = ordered.get(i).temperatureC();
            boolean outOfRange = t.compareTo(min) < 0 || t.compareTo(max) > 0;
            if (outOfRange) {
                if (runStart < 0) {
                    runStart = ordered.get(i).sampledAt();
                }
            } else {
                if (runStart >= 0 && ordered.get(i).sampledAt() - runStart > maxExcursionMillis) {
                    return true;
                }
                runStart = -1;
            }
        }
        return false;
    }
}
