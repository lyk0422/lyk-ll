package com.example.starter.torque.dto;

import java.time.Instant;

/** 见证链复算视图。 */
public record WitnessEventView(
        String pointCode,
        String eventType,
        String witness,
        Instant eventAt,
        String detail
) {
}
