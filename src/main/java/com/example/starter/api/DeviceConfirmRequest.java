package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 设备确认请求：deviceAmount 为设备实际入账数。与清点数、计划数三方一致方可核销。
 */
public record DeviceConfirmRequest(
        @NotBlank String requestId,
        @NotNull @PositiveOrZero Long deviceAmount
) {
}
