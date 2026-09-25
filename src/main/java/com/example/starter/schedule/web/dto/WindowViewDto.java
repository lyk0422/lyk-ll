package com.example.starter.schedule.web.dto;

import java.time.Instant;

/**
 * 过境预报窗口视图。
 */
public record WindowViewDto(
        long windowId,
        String satelliteCode,
        String stationCode,
        Instant startTime,
        Instant endTime,
        String dayKey) {
}
