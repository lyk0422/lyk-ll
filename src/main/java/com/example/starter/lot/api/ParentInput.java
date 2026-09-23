package com.example.starter.lot.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 转换请求中的父批次消费项。
 *
 * @param lotId           父批次编号
 * @param expectedVersion 调用方预期的父批次版本号
 * @param quantity        本次消费量，三位小数，必须为正
 */
public record ParentInput(
        @NotBlank String lotId,
        @NotNull Long expectedVersion,
        @NotNull @DecimalMin(value = "0.000", inclusive = false)
        @Digits(integer = 16, fraction = 3) BigDecimal quantity
) {
}
