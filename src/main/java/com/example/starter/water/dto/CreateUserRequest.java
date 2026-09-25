package com.example.starter.water.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建取水户请求：annualQuota 为年度许可总量，单位立方米（m³）。
 */
public record CreateUserRequest(
        @NotBlank String name,
        @NotBlank String riverSection,
        @NotNull @DecimalMin(value = "0.001", message = "年度许可总量必须为正") BigDecimal annualQuota) {
}
