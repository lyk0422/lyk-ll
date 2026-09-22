package com.example.starter.bag.model;

/**
 * 消息持久化状态。
 */
public enum MessageStatus {

    /** 已提交，参与正常查询与回放 */
    COMMITTED,
    /** 恢复时隔离出的不完整消息，不参与正常查询与回放 */
    QUARANTINED
}
