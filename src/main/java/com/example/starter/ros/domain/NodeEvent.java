package com.example.starter.ros.domain;

import java.time.LocalDateTime;

/**
 * 资源变更事件,revision 全局递增,订阅方按 revision 升序消费。
 */
public record NodeEvent(
        long revision,
        String nodeId,
        EventType eventType,
        String payloadJson,
        boolean confirmed,
        LocalDateTime createdAt
) {
}
