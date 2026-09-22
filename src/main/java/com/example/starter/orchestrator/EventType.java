package com.example.starter.orchestrator;

/**
 * 编排事件类型，写入 launch_event 表。
 */
public enum EventType {
    PLAN_SUBMITTED,
    PLAN_STARTED,
    PLAN_RESUMED,
    NODE_STARTED,
    NODE_HEALTHY,
    NODE_FAILED,
    NODE_BLOCKED,
    NODE_SKIPPED,
    NODE_CANCELLED,
    COMPENSATION_EXECUTED,
    PLAN_SUCCEEDED,
    PLAN_FAILED,
    PLAN_CANCELLED
}
