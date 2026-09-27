package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 合成航班占用窗：UTC 半开区间 [start, end)。
 *
 * @param start 占用起点（UTC，含）
 * @param end   占用终点（UTC，不含）
 */
public record FlightWindow(
        @NotNull Instant start,
        @NotNull Instant end) {
}
