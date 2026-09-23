package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 保管链事件视图。
 */
public record CustodyEventView(
        String entityType,
        String entityId,
        String eventKind,
        String fromCustodian,
        String toCustodian,
        int versionBefore,
        int versionAfter,
        Instant occurredAt,
        String note) {
}
