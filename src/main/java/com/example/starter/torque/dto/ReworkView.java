package com.example.starter.torque.dto;

import java.time.Instant;

/** 返工台账复算视图。 */
public record ReworkView(
        String pointCode,
        int reworkNo,
        String reason,
        Instant createdAt
) {
}
