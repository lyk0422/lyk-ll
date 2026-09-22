package com.example.starter.arbitration.dto;

/**
 * 安全降级事件视图。
 *
 * @param resourceId       资源标识
 * @param claimId          触发降级的租约ID
 * @param triggerReason    触发原因：LEASE_EXPIRED/COMMAND_TIMEOUT/CONTROLLER_LOST
 * @param appliedPosition  降级后目标位置，NULL 表示无历史位置
 * @param appliedVelocity  降级后目标速度，NULL 表示不适用
 * @param appliedAt        降级执行时刻（epoch毫秒）
 */
public record SafeStateEventView(
        String resourceId,
        long claimId,
        String triggerReason,
        Double appliedPosition,
        Double appliedVelocity,
        long appliedAt) {
}
