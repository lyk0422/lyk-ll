package com.example.starter.ventilation.web;

import java.util.List;

/**
 * 火灾模式会话视图。
 */
public record SessionView(
        long sessionId,
        String zoneCode,
        String activateRequestId,
        String smokeDirection,
        String targetCondition,
        String status,
        boolean fault,
        long activatedAt,
        Long resetCompletedAt,
        Long archivedAt,
        List<CommandView> commands
) {
}
