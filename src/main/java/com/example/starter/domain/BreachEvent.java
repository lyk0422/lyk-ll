package com.example.starter.domain;

import java.time.Instant;

/**
 * 越限预警事件。不可删除、不可修改；持续越限 3 秒触发自动制动。
 */
public record BreachEvent(
        Long id,
        String craneId,
        String zoneId,
        Instant firstDetectedAtUtc,
        Instant lastDetectedAtUtc,
        BreachStatus status,
        Instant resolvedAtUtc,
        Instant createdAtUtc) {
}
