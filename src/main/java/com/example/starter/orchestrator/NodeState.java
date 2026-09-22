package com.example.starter.orchestrator;

/**
 * 节点状态。
 * PENDING：待启动；RUNNING：已启动待健康检查；HEALTHY：启动且健康检查通过；
 * FAILED：健康检查失败；BLOCKED：因前置依赖未成功被阻断；SKIPPED：条件谓词不满足未启动；
 * COMPENSATED：已启动后被执行补偿/回滚；CANCELLED：已取消。
 */
public enum NodeState {
    PENDING,
    RUNNING,
    HEALTHY,
    FAILED,
    BLOCKED,
    SKIPPED,
    COMPENSATED,
    CANCELLED
}
