package com.example.starter.damage.api;

/** 估价结论：修理与全损互斥，判定唯一。 */
public enum EstimateDecision {

    /** 修理（最大单项修理费 ≤ 箱值 30%，恰 30% 亦修理）。 */
    REPAIR,
    /** 全损（最大单项修理费 > 箱值 30%）。 */
    TOTAL_LOSS
}
