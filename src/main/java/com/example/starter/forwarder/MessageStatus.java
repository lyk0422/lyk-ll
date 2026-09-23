package com.example.starter.forwarder;

/**
 * 消息生命周期状态：PENDING 待补发，IN_FLIGHT 已发送待确认，ACKED 已确认不再转发。
 */
public enum MessageStatus {
    PENDING,
    IN_FLIGHT,
    ACKED
}
