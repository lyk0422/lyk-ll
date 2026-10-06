package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 紧急停车救援分段清空确认请求；同一分段须两名不同人员确认。
 */
public record RescueConfirmationRequest(
        @NotBlank String requestId,
        @NotNull Long segmentId,
        @NotBlank String operator) {
}
