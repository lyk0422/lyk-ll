package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 更新分区顶托门槛请求：携带期望版本，成功变更加1。
 *
 * @param requestId      幂等键
 * @param threshold      新顶托门槛潮位（米，非负）
 * @param expectedVersion 期望的当前工况版本（从1开始）
 */
public record TideControlUpdateRequest(
        @NotBlank String requestId,
        @NotNull @DecimalMin(value = "0.0", message = "顶托门槛必须非负") BigDecimal threshold,
        @NotNull Integer expectedVersion
) {
}
