package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 受控加注计划请求：先预占所需量，预占后可用量不得低于安全储备（恰等允许）。
 *
 * @param tankCode        储罐编码
 * @param requestId       幂等键
 * @param expectedVersion 储罐乐观锁期望版本
 * @param plannedAt       计划时刻（业务时间，精确时刻参与指纹）
 * @param requiredQtyKg   计划预占量 kg，须为正，至多三位小数
 */
public record TankPlanRequest(
        @NotBlank String tankCode,
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull LocalDateTime plannedAt,
        @NotNull @DecimalMin("0.001") BigDecimal requiredQtyKg) {
}
