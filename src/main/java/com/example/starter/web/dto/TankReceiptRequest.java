package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 加注计划执行回执请求：预占转实际消耗并释放未用部分，实际量不得大于预占。
 *
 * @param planId          计划ID
 * @param requestId       幂等键
 * @param expectedVersion 储罐乐观锁期望版本
 * @param actualQtyKg     实际消耗量 kg，0≤实际量≤预占量
 * @param executedAt      执行时刻（业务时间，精确时刻参与指纹）
 */
public record TankReceiptRequest(
        @NotNull Long planId,
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull @DecimalMin("0.0") BigDecimal actualQtyKg,
        @NotNull LocalDateTime executedAt) {
}
