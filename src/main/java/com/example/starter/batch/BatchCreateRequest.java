package com.example.starter.batch;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 产地报备请求。requestId 为幂等键，指纹覆盖全部业务字段。
 */
public record BatchCreateRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "产地不能为空") String origin,
        @NotBlank(message = "品种不能为空") String variety,
        @NotNull(message = "数量不能为空") @Positive(message = "数量必须为正") BigDecimal quantity) {
}
