package com.example.starter.ros;

/**
 * 单个目标节点在校验/提交流程中的结果状态。
 */
public enum OutcomeStatus {
    /** 节点接受候选配置并已提交 */
    ACCEPTED,
    /** 节点拒绝候选配置（含节点侧拒绝理由） */
    REJECTED,
    /** 节点在配置时限内无响应 */
    TIMEOUT,
    /** expectedVersion 与当前已提交版本不一致 */
    VERSION_CONFLICT
}
