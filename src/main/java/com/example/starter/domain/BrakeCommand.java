package com.example.starter.domain;

import java.time.Instant;

/**
 * 自动制动指令。同一越限事件只下发一次（breachEventId 唯一）。
 */
public record BrakeCommand(
        Long id,
        Long breachEventId,
        String craneId,
        String zoneId,
        Instant issuedAtUtc,
        String reason) {
}
