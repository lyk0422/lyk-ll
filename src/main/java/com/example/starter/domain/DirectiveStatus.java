package com.example.starter.domain;

/**
 * 限功率指令状态：EXECUTING 执行中；SUPERSEDED 被更严格限值取代；RELEASED 已解除。
 */
public enum DirectiveStatus {
    EXECUTING,
    SUPERSEDED,
    RELEASED
}
