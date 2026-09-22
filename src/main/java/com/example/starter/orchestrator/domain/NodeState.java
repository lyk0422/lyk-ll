package com.example.starter.orchestrator.domain;

/**
 * 计划内单个节点的生命周期状态。
 */
public enum NodeState {
    /** 尚未启动 */
    PENDING,
    /** 启动指令已成功发出（模拟进程已拉起），尚未确认健康 */
    STARTED,
    /** 启动成功且健康检查通过（已确认成功，恢复时不得重复启动） */
    SUCCESS,
    /** 启动失败或启动成功后健康检查失败 */
    FAILED,
    /** 未被启动：前置失败传播阻断或条件不满足 */
    SKIPPED,
    /** 已启动节点被回滚补偿后的状态（compensated=1，恢复时不得重复补偿） */
    COMPENSATED,
    /** 未启动即被取消，或已启动节点取消并补偿完成后的最终状态 */
    CANCELLED
}
