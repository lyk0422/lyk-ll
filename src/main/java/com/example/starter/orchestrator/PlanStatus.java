package com.example.starter.orchestrator;

/**
 * 计划状态。
 * READY：已解析 DAG 待执行；RUNNING：执行中；SUCCEEDED：全部节点成功或按条件跳过；
 * FAILED：存在失败或被依赖失败阻断的节点；CANCELLED：已取消。
 */
public enum PlanStatus {
    READY,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
