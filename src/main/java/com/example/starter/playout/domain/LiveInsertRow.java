package com.example.starter.playout.domain;

import java.time.Instant;

/**
 * 直播插播行。
 */
public record LiveInsertRow(
        long id,
        String channel,
        String requestId,
        int priority,
        Instant startTime,
        Instant endTime,
        String endKind,
        String fingerprint,
        Long firstSequence,
        Instant createdAt) {
}
