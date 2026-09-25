package com.example.starter.portability.web;

import java.time.Instant;

import com.example.starter.portability.FlowEvent;

/**
 * 流程事件视图，按 id 升序返回。
 */
public record EventView(
        Long id,
        String eventType,
        String detail,
        Instant createdAt) {

    public static EventView from(FlowEvent event) {
        return new EventView(event.id(), event.eventType(), event.detail(), event.createdAt());
    }
}
