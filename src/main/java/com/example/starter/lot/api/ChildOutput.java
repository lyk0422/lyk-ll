package com.example.starter.lot.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 转换请求中的子批次产出项。
 *
 * @param lotId    新子批次编号，必须不存在（与现有批次冲突则整笔回滚）
 * @param quantity 本次产出量，三位小数，必须为正
 */
public record ChildOutput(
        @NotBlank String lotId,
        @NotNull @DecimalMin(value = "0.000", inclusive = false)
        @Digits(integer = 16, fraction = 3) BigDecimal quantity
) {
}
