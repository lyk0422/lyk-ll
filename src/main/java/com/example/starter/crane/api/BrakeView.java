package com.example.starter.crane.api;

import java.time.Instant;

/**
 * 制动指令视图；同一越限事件只下发一次。
 */
public record BrakeView(
        Long id,
        String commandCode,
        Long warningId,
        String craneCode,
        String zoneCode,
        Instant issuedAt) {
}
