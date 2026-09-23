package com.example.starter.forwarder;

import java.time.LocalDateTime;

/**
 * 持久化消息行的只读视图。
 */
public record MessageView(
        long id,
        String deviceId,
        String topic,
        long deviceSeq,
        int priority,
        String dedupKey,
        String payload,
        MessageStatus status,
        int attemptCount,
        LocalDateTime inFlightSince,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
