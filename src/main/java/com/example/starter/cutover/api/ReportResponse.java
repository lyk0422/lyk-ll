package com.example.starter.cutover.api;

/**
 * 步骤回报结果视图。
 *
 * @param stepStatus        回报后步骤状态
 * @param applicationStatus 回报后申请状态
 * @param rollbackTriggered 本次回报是否触发自动回退
 * @param rollbackReason    回退判据枚举名，未触发为 null
 * @param lateEvent         true 表示申请已终态，回报仅记迟到事件
 * @param replay            true 表示同键同参重放，返回首次结果
 */
public record ReportResponse(
        Long applicationId,
        Integer stepSeq,
        String stepStatus,
        String applicationStatus,
        boolean rollbackTriggered,
        String rollbackReason,
        boolean lateEvent,
        boolean replay
) {
}
