package com.example.starter.torque.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 自检/互检签署请求；signType 取 SELF 或 MUTUAL。
 */
public record InspectionRequest(
        @NotBlank String requestId,
        @NotBlank String signType,
        @NotBlank String inspector
) {
}
