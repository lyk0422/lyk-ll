package com.example.starter.damage.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 到港登记请求。 */
public record RegisterRequest(
        @NotBlank String requestId,
        @NotBlank String containerNo,
        @NotNull @Positive BigDecimal containerValue,
        /** 到港时刻（UTC 毫秒）；为空取服务端当前时刻。 */
        Long arrivedAtUtc) {
}
