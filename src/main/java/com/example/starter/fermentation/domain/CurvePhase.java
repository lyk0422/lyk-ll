package com.example.starter.fermentation.domain;

/**
 * 工艺曲线阶段，与 curve_segment.phase 及 temperature_reading.phase 对应。
 */
public enum CurvePhase {

    /** 主酵阶段。 */
    MAIN_FERMENT,
    /** 降温阶段。 */
    COOLING,
    /** 后熟阶段。 */
    MATURATION
}
