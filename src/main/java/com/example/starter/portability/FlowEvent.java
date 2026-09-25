package com.example.starter.portability;

import java.time.Instant;

/**
 * 流程事件实体，对应 flow_event 表，按 id 升序即事件发生顺序。
 */
public record FlowEvent(
        Long id,
        Long flowId,
        String eventType,
        String detail,
        Instant createdAt) {
}
