package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 投诉登记请求。sampledAt 为 UTC 采样时刻；evidenceRef 缺失时不进入正式处置。
 */
public record ComplaintRequest(
        @NotBlank String requestId,
        @NotBlank String stationId,
        @NotNull BigDecimal frequencyMhz,
        @NotNull Instant sampledAt,
        @NotNull BigDecimal fieldStrengthDbuv,
        String evidenceRef) {
}
