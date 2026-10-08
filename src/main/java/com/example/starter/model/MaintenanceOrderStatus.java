package com.example.starter.model;

/**
 * 检修封锁单状态。
 */
public enum MaintenanceOrderStatus {
    /** 未终态：含未生效（当前时刻早于 start）与生效封锁中（已到 start 且未验收完成） */
    SCHEDULED,
    /** 双验收完成：仅解除检修自身阻断，不影响风速管制 */
    COMPLETED,
    /** 未生效时取消：不产生阻断 */
    CANCELLED
}
