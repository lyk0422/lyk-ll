package com.example.starter.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.api.PlanProfileStep;
import com.example.starter.domain.DivePhase;

/**
 * 减压方案与速率的纯函数计算，均可脱离数据库独立复算。
 */
public final class PlanCalculator {

    /** 安全停留深度（米）。 */
    public static final int SAFETY_STOP_DEPTH_M = 5;
    /** 安全停市场（秒）。 */
    public static final long SAFETY_STOP_SECONDS = 180;

    private PlanCalculator() {
    }

    /**
     * 计算升降段速率（m/min），保留 4 位小数；必须先保证起止深度与相位一致。
     */
    public static BigDecimal rateMetersPerMin(DivePhase phase, int startDepthM, int endDepthM,
                                              long startedAt, long endedAt) {
        long seconds = endedAt - startedAt;
        double distance = Math.abs(endDepthM - startDepthM);
        double rate = distance * 60.0d / seconds;
        return BigDecimal.valueOf(rate).setScale(4, RoundingMode.HALF_UP);
    }

    /** 速率是否超限：恰 18.0 合法，仅严格大于 18 告警。 */
    public static boolean isRateAlarm(BigDecimal rate) {
        return rate.compareTo(BigDecimal.valueOf(DiveRules.MAX_RATE_M_PER_MIN)) > 0;
    }

    /**
     * 依据深度、水底时长与减压表条目生成可复算的合成减压剖面。
     *
     * @param depthM        作业深度（米）
     * @param bottomMinutes 水底时长（分钟）
     * @param ndlMinutes    减压表免减压极限（分钟）
     * @param safetyStop    减压表是否建议安全停留
     */
    public static List<PlanProfileStep> profile(int depthM, int bottomMinutes, int ndlMinutes, boolean safetyStop) {
        List<PlanProfileStep> steps = new ArrayList<>();
        long descendSeconds = Math.round(depthM * 60.0d / DiveRules.MAX_RATE_M_PER_MIN);
        steps.add(new PlanProfileStep(DivePhase.DESCEND.name(), 0, depthM, descendSeconds,
                "以恰 18m/min 合成下潜剖面"));
        steps.add(new PlanProfileStep(DivePhase.BOTTOM.name(), depthM, depthM, bottomMinutes * 60L,
                "水底作业 " + bottomMinutes + " 分钟，免减压极限 " + ndlMinutes + " 分钟"));
        long ascendToSafety = Math.round((depthM - SAFETY_STOP_DEPTH_M) * 60.0d / DiveRules.MAX_RATE_M_PER_MIN);
        int currentDepth = depthM;
        if (safetyStop && depthM > SAFETY_STOP_DEPTH_M) {
            steps.add(new PlanProfileStep(DivePhase.ASCEND.name(), currentDepth, SAFETY_STOP_DEPTH_M,
                    ascendToSafety, "以恰 18m/min 合成上升至安全停留深度"));
            steps.add(new PlanProfileStep("SAFETY_STOP", SAFETY_STOP_DEPTH_M, SAFETY_STOP_DEPTH_M,
                    SAFETY_STOP_SECONDS, "5 米安全停留 3 分钟"));
            currentDepth = SAFETY_STOP_DEPTH_M;
        }
        long ascendSeconds = Math.round(currentDepth * 60.0d / DiveRules.MAX_RATE_M_PER_MIN);
        steps.add(new PlanProfileStep(DivePhase.ASCEND.name(), currentDepth, 0, ascendSeconds,
                "以恰 18m/min 合成出水剖面"));
        return List.copyOf(steps);
    }

    /**
     * 复算 24 小时滚动窗口内与 [windowStart, windowEnd) 重叠的台账秒数之和。
     * 同一潜水员的配对生效台账区间互不重叠时即为累计水下时长。
     */
    public static long overlapSeconds(long startedAt, long endedAt, long windowStart, long windowEnd) {
        long lo = Math.max(startedAt, windowStart);
        long hi = Math.min(endedAt, windowEnd);
        return Math.max(0L, hi - lo);
    }
}
