package com.example.starter.ventilation.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 火灾模式全量视图：含状态、冲突说明与按预案排序的设备指令。
 */
public record ModeView(
        Long id,
        String requestId,
        String zoneCode,
        String smokeFlowDirection,
        String targetCondition,
        String status,
        String conflictDetail,
        LocalDateTime createdAt,
        LocalDateTime resetStartedAt,
        LocalDateTime resetCompletedAt,
        LocalDateTime archivedAt,
        List<CommandView> commands) {
}
