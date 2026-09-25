package com.example.starter.museum.api;

import com.example.starter.museum.domain.WorkOrderStatus;

import java.time.Instant;

/** 保护工单视图。deadline=触发时刻+24h（UTC）。 */
public record WorkOrderResponse(
        String orderNo,
        String alertNo,
        String showcaseNo,
        WorkOrderStatus status,
        Instant deadline,
        Instant confirmedAt,
        String handler,
        String note,
        boolean showcaseFrozen
) {
}
