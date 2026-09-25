package com.example.starter.margin.domain;

/**
 * 触发强制平仓的原因。
 */
public enum LiquidationReason {
    /** 维持担保比例低于 110%，跳过追保直接强平 */
    BELOW_110,
    /** 追保通知逾期未补足 */
    OVERDUE
}
