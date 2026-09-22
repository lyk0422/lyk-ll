package com.example.starter.orchestrator.domain;

/**
 * 计划整体状态。
 */
public enum PlanStatus {
    /** 已落库尚未开始执行 */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 全部应启动节点启动且健康检查通过（条件不满足而跳过不影响成功） */
    SUCCESS,
    /** 存在节点启动/健康检查失败，且后继已阻断、补偿已执行 */
    FAILED,
    /** 收到取消请求，未启动节点已取消、已启动节点已补偿 */
    CANCELLED
}
