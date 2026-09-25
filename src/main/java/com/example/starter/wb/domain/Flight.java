package com.example.starter.wb.domain;

import java.time.LocalDateTime;

/**
 * 合成航班。
 */
public record Flight(
        long id,
        String flightNo,
        String origin,
        String destination,
        long aircraftId,
        LocalDateTime scheduledTime
) {
}
