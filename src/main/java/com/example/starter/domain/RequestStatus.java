package com.example.starter.domain;

/**
 * 封锁申请生命周期状态。
 *
 * <p>合法迁移：
 * <ul>
 *     <li>PENDING → APPROVED（审批）/ CANCELLED（取消）/ PREEMPTED（被紧急申请抢占）</li>
 *     <li>APPROVED → ACTIVE（激活）/ CANCELLED（取消）/ PREEMPTED（被紧急申请抢占）</li>
 *     <li>ACTIVE → COMPLETED（完成）；激活后不可取消、不可抢占</li>
 * </ul>
 * COMPLETED、CANCELLED、PREEMPTED 为终态。
 */
public enum RequestStatus {
    PENDING,
    APPROVED,
    ACTIVE,
    COMPLETED,
    CANCELLED,
    PREEMPTED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == PREEMPTED;
    }
}
