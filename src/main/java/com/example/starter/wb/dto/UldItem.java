package com.example.starter.wb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 装配图逐项：一个集装器装入一个舱位。
 *
 * @param uldNo        集装器唯一编号（实物标识，合法文本可含分隔符）
 * @param uldType      集装器类型代码，须为该舱位允许类型
 * @param positionCode 舱位代码
 * @param tareKg       皮重，非负整数 kg
 * @param netKg        净重，正整数 kg
 */
public record UldItem(
        @NotBlank String uldNo,
        @NotBlank String uldType,
        @NotBlank String positionCode,
        @NotNull @PositiveOrZero Integer tareKg,
        @NotNull @Positive Integer netKg
) {
}
