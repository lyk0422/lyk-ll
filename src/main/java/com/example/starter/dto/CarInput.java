package com.example.starter.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 车辆输入(合成数据)。
 *
 * @param carNo       车辆号
 * @param lengthCoeff 换长
 * @param weightTons  总重(吨)
 * @param brakeCutout 是否关门车
 */
public record CarInput(
        @NotBlank String carNo,
        @NotNull @DecimalMin("0.01") BigDecimal lengthCoeff,
        @NotNull @DecimalMin("0.01") BigDecimal weightTons,
        boolean brakeCutout) {
}
