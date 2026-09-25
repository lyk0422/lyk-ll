package com.example.starter.ventilation.web;

import java.util.List;

/**
 * 事件流水视图。
 */
public record EventLogView(
        long id,
        String eventType,
        Long sessionId,
        String zoneCode,
        String requestId,
        String detail,
        long eventTime
) {

    /** 事件查询结果包装。 */
    public record Page(List<EventLogView> events, boolean hasMore) {
    }
}
