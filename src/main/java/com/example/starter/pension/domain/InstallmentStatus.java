package com.example.starter.pension.domain;

/**
 * 分期期次状态。
 */
public enum InstallmentStatus {

    /** 待付。 */
    PENDING,
    /** 已出款，终态，每期最多出款一次。 */
    PAID,
    /** 出款失败，可使用新的指令 requestId 重试。 */
    FAILED,
    /** 被继承申领终止（终态），不再出款。 */
    TERMINATED
}
