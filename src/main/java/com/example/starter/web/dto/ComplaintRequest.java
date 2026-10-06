package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 投诉登记请求。requestId 为幂等键，指纹覆盖全部业务字段。
 */
public record ComplaintRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull BigDecimal frequencyMhz,
        @NotNull OffsetDateTime sampledAt,
        @NotNull BigDecimal fieldStrengthDbuv,
        String evidenceId) {
}
