package com.example.starter.cutover.api;

/**
 * 回退指令视图。
 *
 * @param applicationStatus 下达后申请状态（ROLLED_BACK/WINDOW_OVERDUE）
 * @param triggerReason     回退判据枚举名
 * @param detail            判据命中明细
 * @param frozenStepCount   同事务冻结的未完成步骤数
 * @param alreadyRolledBack true 表示回退记录此前已存在，本次为并发落败方
 */
public record RollbackResponse(
        Long applicationId,
        String applicationStatus,
        String triggerReason,
        String detail,
        int frozenStepCount,
        boolean alreadyRolledBack
) {
}
