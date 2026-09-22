package com.example.starter.arbitration.api;

import java.util.List;

/**
 * 系统状态快照。
 *
 * @param estopActive     是否处于急停
 * @param activeLeases    当前有效租约
 * @param pendingCommands 待执行指令数量
 * @param safetyEvents    已执行的安全降级事件
 */
public record SystemStatusResponse(
        boolean estopActive,
        List<LeaseView> activeLeases,
        int pendingCommands,
        List<SafetyEventView> safetyEvents) {

    /** 租约视图。 */
    public record LeaseView(
            String controllerId,
            String resourceName,
            String requestId,
            int priority,
            long expiresAtMs) {
    }

    /** 安全降级事件视图。 */
    public record SafetyEventView(
            String eventKey,
            String reason,
            String resourceName,
            String controllerId,
            String action,
            Double targetPosition,
            long eventMs) {
    }
}
