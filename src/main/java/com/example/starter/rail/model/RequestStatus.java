package com.example.starter.rail.model;

/**
 * 封锁申请生命周期状态。
 * <ul>
 *   <li>PENDING：已提交，尚未审批；</li>
 *   <li>APPROVED：已审批，占用尚未开始；</li>
 *   <li>ACTIVE：已激活，区间正在实际占用；</li>
 *   <li>COMPLETED：已完成（终态）；</li>
 *   <li>CANCELLED：已取消（终态）；</li>
 *   <li>PREEMPTED：被更高优先级紧急申请抢占（终态）。</li>
 * </ul>
 */
public enum RequestStatus {
    PENDING,
    APPROVED,
    ACTIVE,
    COMPLETED,
    CANCELLED,
    PREEMPTED;

    /** 是否为不可再迁移的终态。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == PREEMPTED;
    }
}
