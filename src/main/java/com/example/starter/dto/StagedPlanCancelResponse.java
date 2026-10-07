package com.example.starter.dto;

/**
 * 分段计划取消/重放响应：status 为 CANCELED，机组占用已释放，实际功率保持最后确认值。
 */
public record StagedPlanCancelResponse(
        String requestId,
        String orderNo,
        Long planId,
        int planVersion,
        String planStatus,
        boolean replayed) {

    public StagedPlanCancelResponse asReplay() {
        return new StagedPlanCancelResponse(requestId, orderNo, planId, planVersion, planStatus, true);
    }
}
