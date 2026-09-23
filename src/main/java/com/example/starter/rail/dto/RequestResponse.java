package com.example.starter.rail.dto;

import com.example.starter.rail.model.PossessionRequest;

import java.time.Instant;
import java.util.List;

/**
 * 申请视图响应。
 */
public record RequestResponse(
        String requestId,
        List<String> sectionIds,
        Instant start,
        Instant end,
        long bufferBeforeMs,
        long bufferAfterMs,
        int priority,
        int version,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    public static RequestResponse from(PossessionRequest r) {
        return new RequestResponse(
                r.getRequestId(),
                List.copyOf(r.getSectionIds()),
                r.startInstant(),
                r.endInstant(),
                r.getBufferBeforeMs(),
                r.getBufferAfterMs(),
                r.getPriority(),
                r.getVersion(),
                r.getStatus().name(),
                Instant.ofEpochMilli(r.getCreatedAt()),
                Instant.ofEpochMilli(r.getUpdatedAt())
        );
    }
}
