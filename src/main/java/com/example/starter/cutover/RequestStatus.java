package com.example.starter.cutover;

/**
 * 割接申请主状态机：
 * SUBMITTED 已申请 -> IMPACT_ANALYZED 影响分析完成 -> APPROVED 已批准
 * -> EXECUTING 窗口执行中 -> VERIFIED 验证通过 -> SEALED 封板（终态）。
 * 失败/异常分支：REJECTED 审批或冲突拒绝（终态）；ROLLING_BACK 回退中
 * -> ROLLED_BACK 回退完成（终态）；WINDOW_EXPIRED 窗口超期后强制回退完成（终态）。
 * 批次内尚未开始的申请被失败前置传播阻断 -> DEPENDENCY_BLOCKED 依赖阻断（终态，不写步骤）。
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
