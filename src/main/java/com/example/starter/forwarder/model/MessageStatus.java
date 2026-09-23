package com.example.starter.forwarder.model;

/**
 * 设备消息在转发器中的生命周期状态。
 */
public enum MessageStatus {
    /** 离线缓存中，等待补发。 */
    PENDING,
    /** 已发送给上游，等待 ACK；超时或重启后回到 PENDING 重试。 */
    IN_FLIGHT,
    /** 已被上游明确确认，不再转发。 */
    ACKNOWLEDGED
}
