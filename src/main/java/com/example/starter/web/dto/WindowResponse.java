package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.domain.BerthWindow;

/**
 * 窗口响应。时间均为 UTC ISO-8601。
 */
public record WindowResponse(
        long id,
        String requestId,
        String vesselName,
        long berthId,
        Instant startAt,
        Instant endAt,
        double draftM,
        int pilotsRequired,
        String status,
        List<Long> pilotIds,
        Instant createdAt,
        Instant updatedAt) {

    public static WindowResponse from(BerthWindow w) {
        return new WindowResponse(w.id(), w.requestId(), w.vesselName(), w.berthId(),
                w.startAt(), w.endAt(), w.draftM(), w.pilotsRequired(), w.status().name(),
                w.pilotIds(), w.createdAt(), w.updatedAt());
    }
}
