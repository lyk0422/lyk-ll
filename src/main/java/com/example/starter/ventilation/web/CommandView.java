package com.example.starter.ventilation.web;

/**
 * 设备指令视图。
 */
public record CommandView(
        long commandId,
        int seqNo,
        String phase,
        String deviceCode,
        String deviceType,
        String action,
        int attempt,
        String status,
        Long sentAt,
        Long ackDueAt,
        Long ackedAt,
        Long faultAt
) {
}
