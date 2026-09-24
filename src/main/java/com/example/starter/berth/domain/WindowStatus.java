package com.example.starter.berth.domain;

/**
 * 靠泊窗口状态机。
 *
 * <p>流转：PENDING（待审批）→ APPROVED（已批准）→ ACTIVE（已生效，到达起始时刻自动转换）→
 * COMPLETED（已完成，登记离泊）。PENDING/APPROVED 可被 REJECTED（驳回）或 CANCELLED（取消）。
 * 已批准/已生效窗口改期后原窗口转为 SUPERSEDED（已被改期取代）。
 * REJECTED、CANCELLED、COMPLETED、SUPERSEDED 均为终态，不得复活。</p>
 */
public enum WindowStatus {

    /** 待审批：申请已通过冲突校验，等待审批。 */
    PENDING,
    /** 已批准：审批通过，尚未到达起始时刻。 */
    APPROVED,
    /** 已生效：已批准窗口到达起始时刻。 */
    ACTIVE,
    /** 已完成：已登记离泊，终态。 */
    COMPLETED,
    /** 已驳回：审批驳回，终态。 */
    REJECTED,
    /** 已取消：申请人取消，终态。 */
    CANCELLED,
    /** 已被改期取代：改期新申请成功后原窗口转入该终态。 */
    SUPERSEDED;

    /**
     * 判断是否为终态（驳回、取消、完成、被取代）。
     *
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED || this == CANCELLED || this == SUPERSEDED;
    }
}
