package com.example.starter.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 批次申报请求。
 *
 * @param requestId       申报幂等键
 * @param lotNo           批次号
 * @param applicant       申报主体
 * @param varietyName     品种名称
 * @param fieldCode       地块编号
 * @param areaMu          申报面积（亩）
 * @param availableLabels 初始可用标签数量（枚）
 */
public record DeclareLotRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 40) String lotNo,
        @NotBlank @Size(max = 100) String applicant,
        @NotBlank @Size(max = 100) String varietyName,
        @NotBlank @Size(max = 60) String fieldCode,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal areaMu,
        @PositiveOrZero int availableLabels) {
}
