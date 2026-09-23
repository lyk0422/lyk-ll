package com.example.starter.lot.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 建批次请求。
 *
 * @param lotId      批次唯一编号
 * @param unit       计量单位
 * @param quantity   初始数量，固定三位小数且必须为正
 */
public record CreateLotRequest(
        @NotBlank String lotId,
        @NotBlank String unit,
        @NotNull @DecimalMin(value = "0.000", inclusive = false)
        @Digits(integer = 16, fraction = 3) BigDecimal quantity
) {
}
