package com.example.starter.dye.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 染缸建档请求（合成设备数据维护入口）。
 *
 * @param capacityKg 额定容量，单位千克织物
 */
public record VatUpsertRequest(
        @NotBlank String vatCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal capacityKg) {
}
