package com.example.starter.repo;

import java.time.Instant;

import com.example.starter.domain.EntityType;
import com.example.starter.domain.EventKind;

/**
 * 保管链事件行模型。
 */
public record CustodyEventRow(
        long id,
        EntityType entityType,
        String entityId,
        EventKind eventKind,
        String fromCustodian,
        String toCustodian,
        int versionBefore,
        int versionAfter,
        Instant occurredAt,
        String note) {
}
