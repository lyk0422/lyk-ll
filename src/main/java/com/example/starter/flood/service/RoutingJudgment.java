package com.example.starter.flood.service;

import com.example.starter.flood.domain.CapacityJudgment;
import com.example.starter.flood.domain.DangerJudgment;
import com.example.starter.flood.domain.IndicatorJudgment;
import java.math.BigDecimal;

/**
 * 调洪判定纯计算：集中实现边界口径，保证演算与复算完全一致。
 *
 * <p>库容口径：设计洪水量 &lt; 可用调洪库容 判安全；恰等判临界；大于判须泄流。</p>
 * <p>指标口径：实测值 &gt; 安全下限判安全；恰等下限判临界；小于下限判险。</p>
 * <p>综合口径：任一指标判险→险；否则库容须泄流→须泄流；否则任一项临界→临界；全部安全→安全。</p>
 */
public final class RoutingJudgment {

    private RoutingJudgment() {
    }

    /**
     * 库容判定。
     *
     * @param designFloodVolume 设计洪水量，立方米
     * @param availableCapacity 当前水位反查可用调洪库容，立方米
     */
    public static CapacityJudgment capacity(BigDecimal designFloodVolume,
                                            BigDecimal availableCapacity) {
        int cmp = designFloodVolume.compareTo(availableCapacity);
        if (cmp < 0) {
            return CapacityJudgment.SAFE;
        }
        if (cmp == 0) {
            return CapacityJudgment.CRITICAL;
        }
        return CapacityJudgment.RELEASE_REQUIRED;
    }

    /**
     * 单项监测指标判定；无测量值（null）时不参与判定，记为 SAFE。
     */
    public static IndicatorJudgment indicator(BigDecimal measured, BigDecimal safeLimit) {
        if (measured == null) {
            return IndicatorJudgment.SAFE;
        }
        int cmp = measured.compareTo(safeLimit);
        if (cmp < 0) {
            return IndicatorJudgment.DANGER;
        }
        if (cmp == 0) {
            return IndicatorJudgment.CRITICAL;
        }
        return IndicatorJudgment.SAFE;
    }

    /**
     * 综合判定合并。
     */
    public static DangerJudgment combine(CapacityJudgment capacityJudgment,
                                         IndicatorJudgment dryBeach,
                                         IndicatorJudgment phreatic) {
        if (dryBeach == IndicatorJudgment.DANGER || phreatic == IndicatorJudgment.DANGER) {
            return DangerJudgment.DANGER;
        }
        if (capacityJudgment == CapacityJudgment.RELEASE_REQUIRED) {
            return DangerJudgment.RELEASE_REQUIRED;
        }
        if (capacityJudgment == CapacityJudgment.CRITICAL
                || dryBeach == IndicatorJudgment.CRITICAL
                || phreatic == IndicatorJudgment.CRITICAL) {
            return DangerJudgment.CRITICAL;
        }
        return DangerJudgment.SAFE;
    }
}
