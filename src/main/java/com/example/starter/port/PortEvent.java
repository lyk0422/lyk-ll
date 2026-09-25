package com.example.starter.port;

import java.time.Instant;

/**
 * 流程事件（port_event 表行映射），按 id 升序即事件发生顺序。
 */
public record PortEvent(
        long id,
        long portRequestId,
        String eventType,
        String detail,
        Instant createdAt) {
}
