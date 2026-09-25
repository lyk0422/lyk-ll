package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * 编组核对请求，携带合成运统1数据。
 *
 * @param requestId           幂等请求键
 * @param expectedCarCount    运统1辆数
 * @param expectedTotalLength 运统1换长
 * @param expectedTotalWeight 运统1总重，单位吨
 */
public record ConsistCheckRequest(
        @NotBlank String requestId,
        @NotNull @PositiveOrZero Integer expectedCarCount,
        @NotNull @PositiveOrZero BigDecimal expectedTotalLength,
        @NotNull @PositiveOrZero BigDecimal expectedTotalWeight) {
}
