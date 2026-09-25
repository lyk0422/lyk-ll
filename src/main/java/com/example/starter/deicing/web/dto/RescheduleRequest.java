package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * HOT 风险航班重排起飞时刻请求体。
 */
public record RescheduleRequest(
        @NotBlank String requestId,
        @NotNull Instant newTargetTakeoffTime) {
}
