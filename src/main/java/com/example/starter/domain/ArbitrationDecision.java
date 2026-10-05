package com.example.starter.domain;

/**
 * 下发前预演与冲突仲裁结论。结论与策略状态推进在同一事务内完成（拒绝路径无状态推进，
 * 留档独立提交），并随策略版本留档可复算。
 */
public enum ArbitrationDecision {
    /** 预演通过：估算能耗不超过当日剩余预算（或当日未分配预算），且无告警处置冲突。 */
    APPROVED,
    /** 估算能耗超过当日剩余预算：直接拒绝下发，策略保持 VALIDATED。 */
    REJECTED_BUDGET,
    /** 同分区存在进行中告警处置且新策略与之不兼容：下发挂起等待处置完结，无副作用。 */
    SUSPENDED_ALARM
}
