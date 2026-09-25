package com.example.starter.water.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 月度取水计划申报请求。
 *
 * @param requestId 幂等键；指纹覆盖 requestId、取水户、自然月（UTC，yyyy-MM）、申报水量
 * @param userId    取水户 ID
 * @param month     自然月，UTC，格式 yyyy-MM
 * @param volume    申报水量，单位立方米（m³），必须为正
 */
public record DeclarationRequest(
        @NotBlank String requestId,
        @NotNull Long userId,
        @NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "月份格式必须为 yyyy-MM（UTC）") String month,
        @NotNull @DecimalMin(value = "0.001", message = "申报水量必须为正") BigDecimal volume) {
}
