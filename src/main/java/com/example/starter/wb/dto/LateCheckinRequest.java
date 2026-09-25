package com.example.starter.wb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 关闭后迟到的值机变化上报。
 */
public record LateCheckinRequest(
        @NotBlank String requestId,
        int passengerCount,
        @NotNull BigDecimal weightKg,
        String detail
) {
}
