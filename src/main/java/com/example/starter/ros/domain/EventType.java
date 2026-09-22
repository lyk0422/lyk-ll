package com.example.starter.ros.domain;

/**
 * 节点资源事件类型。
 * REGISTERED:节点注册(携带全部资源);STATE_CHANGED:合法生命周期迁移;
 * DEREGISTERED:主动注销;CRASHED:异常退出(单次清理事件,负载含被清理资源)。
 */
public enum EventType {
    REGISTERED,
    STATE_CHANGED,
    DEREGISTERED,
    CRASHED
}
