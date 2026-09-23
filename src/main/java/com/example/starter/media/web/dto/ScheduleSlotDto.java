package com.example.starter.media.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 排期中的单个半开播出时段 [slotStart, slotEnd)。
 */
public record ScheduleSlotDto(
        @NotBlank String assetId,
        @NotBlank String region,
        @NotBlank String platform,
        @NotNull Instant slotStart,
        @NotNull Instant slotEnd
) {
}
