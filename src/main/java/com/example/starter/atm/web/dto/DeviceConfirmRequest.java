package com.example.starter.atm.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 设备确认请求：登记设备实际入账数，单位：分。 */
public record DeviceConfirmRequest(
        @NotBlank String requestId,
        @NotNull @Positive Long deviceAmount
) {
}
