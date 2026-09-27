package com.example.starter.wb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 集装器装配明细录入项。
 *
 * @param uldNumber       集装器编号，同图内唯一、未关闭航班间唯一
 * @param uldType         集装器类型，须为舱位允许类型
 * @param compartmentCode 装载舱位代码
 * @param tareKg          皮重，kg，非负整数
 * @param netKg           净重，kg，正整数
 */
public record AssemblyItemRequest(
        @NotBlank String uldNumber,
        @NotBlank String uldType,
        @NotBlank String compartmentCode,
        @NotNull @PositiveOrZero Integer tareKg,
        @NotNull @Positive Integer netKg
) {
}
