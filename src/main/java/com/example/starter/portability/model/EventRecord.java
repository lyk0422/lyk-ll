package com.example.starter.portability.model;

import java.time.Instant;

/**
 * 流程事件记录，对应 portability_event 表，按 id 升序即发生顺序。
 */
public record EventRecord(
        long id,
        long flowId,
        String eventType,
        String detail,
        Instant createdAt) {
}
