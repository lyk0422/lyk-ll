package com.example.starter.pension.domain;

/**
 * 支付计划状态，{@link #SETTLED} 与 {@link #TERMINATED} 为终态。
 */
public enum PlanStatus {

    /** 已受理，尚未开始出款。 */
    ACCEPTED,
    /** 支付中，至少存在未完成期次。 */
    IN_PROGRESS,
    /** 已结清（终态）：全部期次出款完成。 */
    SETTLED,
    /** 已终止（终态）：继承申领受理后终止剩余分期，已支付部分不回溯。 */
    TERMINATED
}
