package com.example.starter.wb.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 装配图逐项录入：唯一集装器编号、舱位、非负皮重（kg）、正净重（kg）。
 *
 * @param uldNo           集装器唯一编号（前 3 位须为舱位允许的类型代码）
 * @param compartmentCode 装载舱位代码
 * @param tareKg          皮重，非负整数公斤
 * @param netKg           净重，正整数公斤
 */
public record UldItemInput(
        @NotBlank String uldNo,
        @NotBlank String compartmentCode,
        @NotNull @Min(0) Integer tareKg,
        @NotNull @Positive Integer netKg
) {
}
