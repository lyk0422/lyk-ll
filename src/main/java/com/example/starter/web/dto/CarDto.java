package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * 车辆信息（合成数据）。
 *
 * @param carNo       车辆号
 * @param weightTons  重量，单位吨
 * @param length      换长
 * @param valveClosed 是否关门车
 */
public record CarDto(
        @NotBlank String carNo,
        @NotNull @PositiveOrZero BigDecimal weightTons,
        @NotNull @PositiveOrZero BigDecimal length,
        boolean valveClosed) {
}
