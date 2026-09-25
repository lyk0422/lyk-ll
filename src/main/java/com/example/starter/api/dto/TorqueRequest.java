package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 力矩紧固测量请求（也用于归档后迟到测量，迟到时仅记录事件）。
 */
public record TorqueRequest(
        @NotBlank String requestId,
        @NotBlank String cardNo,
        @NotBlank String pointCode,
        @NotNull @Positive BigDecimal measuredTorque,
        @NotBlank String operator,
        @NotBlank String toolCode) {
}
