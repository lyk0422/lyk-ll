package com.example.starter.enums;

/**
 * 设备消息在本地转发队列中的生命周期状态。
 */
public enum MessageStatus {
    /** 已持久化、等待投递。 */
    PENDING,
    /** 已投递给上游适配器、等待明确 ACK。 */
    INFLIGHT,
    /** 已收到明确 ACK，转发完成。 */
    FORWARDED
}
