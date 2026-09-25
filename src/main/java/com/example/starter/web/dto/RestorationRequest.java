package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 市电恢复上报。
 */
public record RestorationRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull Instant restoredAt
) {
}
