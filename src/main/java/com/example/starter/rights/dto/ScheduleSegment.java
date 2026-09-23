package com.example.starter.rights.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 排期中的一个半开时段 [start,end)。
 */
public record ScheduleSegment(
        @NotBlank String assetId,
        @NotBlank String region,
        @NotBlank String platform,
        @NotNull Instant start,
        @NotNull Instant end) {
}
