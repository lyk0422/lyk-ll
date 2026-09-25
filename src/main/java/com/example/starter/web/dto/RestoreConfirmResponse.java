package com.example.starter.web.dto;

/**
 * 复电确认响应。
 *
 * @param workOrderId 工单ID
 * @param restored    双确认是否已齐、复电是否生效
 * @param late        是否为封存后的迟到回报（仅记录迟到事件）
 * @param status      工单当前状态
 * @param replayed    是否为同键同参幂等重放
 */
public record RestoreConfirmResponse(
        long workOrderId,
        boolean restored,
        boolean late,
        String status,
        boolean replayed) {
}
