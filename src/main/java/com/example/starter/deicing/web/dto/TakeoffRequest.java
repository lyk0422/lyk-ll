package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 实际起飞回报。
 *
 * @param actualTakeoffTime 实际起飞时刻（UTC）
 */
public record TakeoffRequest(
        @NotBlank String requestId,
        @NotNull Instant actualTakeoffTime) {
}
