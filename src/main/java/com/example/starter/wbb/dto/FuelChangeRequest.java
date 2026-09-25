package com.example.starter.wbb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 加油量变更请求：油量与重心重算在同一事务内提交，禁止改油不改重心。
 */
public record FuelChangeRequest(
        @NotBlank String requestId,
        @NotNull BigDecimal fuelKg,
        BigDecimal fuelArm
) {
}
