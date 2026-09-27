package com.example.starter.cutover;

/**
 * 割接申请主状态机：
 * SUBMITTED 已申请 -> IMPACT_ANALYZED 影响分析完成 -> APPROVED 已批准
 * -> EXECUTING 窗口执行中 -> VERIFIED 验证通过 -> SEALED 封板（终态）。
 * 失败/异常分支：REJECTED 审批或冲突拒绝（终态）；ROLLING_BACK 回退中
 * -> ROLLED_BACK 回退完成（终态）；WINDOW_EXPIRED 窗口超期后强制回退完成（终态）；
 * DEPENDENCY_BLOCKED 批次内前置回退/超期导致的依赖阻断（尚未开始成员，终态）。
 */
public enum RequestStatus {
    SUBMITTED,
    IMPACT_ANALYZED,
    APPROVED,
    EXECUTING,
    VERIFIED,
    SEALED,
    REJECTED,
    ROLLING_BACK,
    ROLLED_BACK,
    WINDOW_EXPIRED,
    DEPENDENCY_BLOCKED
}
