package com.example.starter.coldchain.service;

import com.example.starter.coldchain.domain.SegmentStatus;
import com.example.starter.coldchain.entity.Shipment;
import com.example.starter.coldchain.entity.TemperatureReading;

import java.time.Duration;
import java.util.List;

/**
 * 分段温度判定（纯逻辑，便于单元测试）：
 * <ol>
 *   <li>序号未从 {@code firstSequence} 连续覆盖到 {@code expectedLastSequence}（缺号）→
 *       {@link SegmentStatus#INCOMPLETE}；</li>
 *   <li>相邻序号读数采样间隔超过最大采样间隔 → {@link SegmentStatus#INCOMPLETE}；</li>
 *   <li>相邻读数之间沿用前一读数温度（阶梯函数），连续超限时长严格超过允许分钟数 →
 *       {@link SegmentStatus#EXCURSION}；</li>
 *   <li>其余为 {@link SegmentStatus#CLEAR}。</li>
 * </ol>
 * 温度等于闭区间端点视为合格；连续超限时长等于允许上限亦视为合格。
 * 调用方须保证读数按序号升序；采样时间倒退由服务层在调用前拒绝。
 */
public final class SegmentEvaluator {

    private SegmentEvaluator() {
    }

    /**
     * 判定段结果。
     *
     * @param shipment              货件阈值配置
     * @param readings              段首末序号区间内实际存在的读数（按序号升序）
     * @param firstSequence         段首序号（含）
     * @param expectedLastSequence  交接声明的段末序号（含）
     */
    public static SegmentStatus evaluate(Shipment shipment, List<TemperatureReading> readings,
                                         long firstSequence, long expectedLastSequence) {
        if (expectedLastSequence < firstSequence || readings.isEmpty()) {
            return SegmentStatus.INCOMPLETE;
        }

        // 1) 缺号检查：数量与逐序号连续性。
        long expectedCount = expectedLastSequence - firstSequence + 1;
        if ((long) readings.size() != expectedCount) {
            return SegmentStatus.INCOMPLETE;
        }
        for (int i = 0; i < readings.size(); i++) {
            if (readings.get(i).sequenceNo() != firstSequence + i) {
                return SegmentStatus.INCOMPLETE;
            }
        }

        // 2) 采样间隔检查（防御性处理非正间隔）。
        long maxIntervalSeconds = shipment.maxSampleIntervalSeconds();
        for (int i = 1; i < readings.size(); i++) {
            long gap = Duration.between(readings.get(i - 1).sampledAt(),
                    readings.get(i).sampledAt()).toSeconds();
            if (gap <= 0 || gap > maxIntervalSeconds) {
                return SegmentStatus.INCOMPLETE;
            }
        }

        // 3) 连续超限时长：区间 [r[i].t, r[i+1].t) 沿用 r[i] 的温度。
        long maxExcursionSeconds = shipment.maxExcursionMinutes() * 60L;
        long continuousExcursionSeconds = 0;
        for (int i = 0; i + 1 < readings.size(); i++) {
            TemperatureReading current = readings.get(i);
            long gapSeconds = Duration.between(current.sampledAt(),
                    readings.get(i + 1).sampledAt()).toSeconds();
            if (outOfRange(current.temperature(), shipment)) {
                continuousExcursionSeconds += gapSeconds;
                if (continuousExcursionSeconds > maxExcursionSeconds) {
                    return SegmentStatus.EXCURSION;
                }
            } else {
                continuousExcursionSeconds = 0;
            }
        }
        return SegmentStatus.CLEAR;
    }

    /** 温度超出闭区间（端点合格）；读数写入时已保证为有限数值。 */
    private static boolean outOfRange(double temperature, Shipment shipment) {
        return temperature < shipment.minTemp() || temperature > shipment.maxTemp();
    }
}
