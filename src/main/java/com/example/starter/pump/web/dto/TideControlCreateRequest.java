package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 创建分区潮汐控制配置请求。创建后工况版本从1开始。
 *
 * @param requestId 幂等键
 * @param zoneCode  流域分区代码
 * @param threshold 顶托门槛潮位（米，非负）
 */
public record TideControlCreateRequest(
        @NotBlank String requestId,
        @NotBlank String zoneCode,
        @NotNull @DecimalMin(value = "0.0", message = "顶托门槛必须非负") BigDecimal threshold
) {
}
