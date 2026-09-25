package com.example.starter.ventilation.dto;

import java.time.LocalDateTime;

/**
 * 单条设备指令视图。
 *
 * @param attempt       下发次数：1 首发，2 超时重发
 * @param blockedReason 跳过/故障原因（如共享风阀冲突、二次超时）
 */
public record CommandView(
        Long id,
        String commandUid,
        String deviceCode,
        String deviceType,
        String targetState,
        String phase,
        Integer stepOrder,
        Integer attempt,
        String status,
        String blockedReason,
        LocalDateTime issuedAt,
        LocalDateTime ackAt,
        LocalDateTime failedAt,
        LocalDateTime createdAt) {
}
