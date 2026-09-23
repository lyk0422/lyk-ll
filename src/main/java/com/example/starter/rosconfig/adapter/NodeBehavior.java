package com.example.starter.rosconfig.adapter;

/**
 * 模拟节点对候选配置的行为。
 */
public enum NodeBehavior {
    /** 接受候选配置 */
    ACCEPT,
    /** 拒绝候选配置 */
    REJECT,
    /** 在配置时限内不响应（模拟挂起） */
    TIMEOUT
}
