package com.example.starter.portability.dto;

import java.time.Instant;

import com.example.starter.portability.model.EventRecord;

/**
 * 流程事件响应，按 id 升序返回。
 */
public record EventResponse(
        long id,
        String eventType,
        String detail,
        Instant createdAt) {

    /**
     * 由事件记录构造响应。
     */
    public static EventResponse from(EventRecord event) {
        return new EventResponse(event.id(), event.eventType(), event.detail(), event.createdAt());
    }
}
