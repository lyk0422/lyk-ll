package com.example.starter.domain;

/**
 * 消息投递状态。
 * <ul>
 *   <li>{@link #PENDING}：已落库待补发；</li>
 *   <li>{@link #IN_FLIGHT}：已发送给上游、等待 ACK。</li>
 * </ul>
 */
public enum MessageStatus {
    PENDING,
    IN_FLIGHT
}
