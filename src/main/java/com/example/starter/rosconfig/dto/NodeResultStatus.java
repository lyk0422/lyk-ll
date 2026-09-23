package com.example.starter.rosconfig.dto;

/**
 * 事务中单个节点的处理结果状态。
 */
public enum NodeResultStatus {
    /** 节点已接受候选配置并完成应用 */
    ACCEPTED,
    /** Schema/类型/必填/依赖等本地校验失败，未触达节点 */
    SCHEMA_REJECTED,
    /** 调用方 expectedVersion 与服务端当前单调版本不一致 */
    VERSION_CONFLICT,
    /** 变更集中包含静态参数 */
    STATIC_PARAM,
    /** 节点主动拒绝候选配置 */
    REJECTED,
    /** 节点在配置时限内无响应 */
    TIMEOUT,
    /** 因其他节点失败导致整笔回滚，该节点未被触达 */
    SKIPPED
}
