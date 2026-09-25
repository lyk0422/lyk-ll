package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/** 入账请求；occurredAt 缺省取服务当前时刻(UTC)。 */
public record PaymentRequest(
        @NotBlank String requestId,
        @NotBlank String channelFlowNo,
        @NotBlank String merchantNo,
        @NotNull @Positive Long amount,
        Instant occurredAt) {
}
