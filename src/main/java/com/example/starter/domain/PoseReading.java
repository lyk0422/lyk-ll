package com.example.starter.domain;

import java.time.Instant;

/**
 * 塔机位姿读数。按（塔机，采集时刻）去重；乱序到达按 tsUtc 归位。
 */
public record PoseReading(
        Long id,
        String craneId,
        Instant tsUtc,
        double hookXM,
        double hookYM,
        Instant receivedAtUtc) {
}
