package com.example.starter.torque.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 力矩紧固测量提交。
 */
public record TighteningRequest(
        @NotBlank String requestId,
        @NotBlank String pointCode,
        @NotNull @Positive BigDecimal measuredTorque,
        @NotBlank String toolNo,
        @NotBlank String operator
) {
}
