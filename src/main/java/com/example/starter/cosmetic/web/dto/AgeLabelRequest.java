package com.example.starter.cosmetic.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 适用年龄标识请求条目（每适用档独立标识；受理时冻结文本摘要）。
 *
 * @param bandCode               适用年龄档编码（INFANT/CHILD）
 * @param labelText              标识文本（受理时取其摘要冻结）
 * @param applicableLowerMonths  标识适用范围下限（整数月，闭区间，0~144）
 * @param applicableUpperMonths  标识适用范围上限（整数月，闭区间，0~144）
 */
public record AgeLabelRequest(
        @NotBlank String bandCode,
        @NotBlank String labelText,
        @NotNull @Min(0) @Max(144) Integer applicableLowerMonths,
        @NotNull @Min(0) @Max(144) Integer applicableUpperMonths) {
}
