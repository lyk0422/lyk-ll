package com.example.starter.api;

/**
 * 减压剖面单步：phase 为 DESCEND/BOTTOM/ASCEND/SAFETY_STOP；
 * startDepthM/endDepthM 单位米，durationSeconds 单位秒，note 为合成说明。
 */
public record PlanProfileStep(
        String phase,
        int startDepthM,
        int endDepthM,
        long durationSeconds,
        String note) {
}
