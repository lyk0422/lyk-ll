package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 田间检验提交请求（品种纯度，百分数）。
 *
 * @param requestId 幂等键
 * @param lotNo     批次编号
 * @param purity    品种纯度（%），0~100，最多三位小数
 * @param round     轮次：FIRST=首检，RETEST=不合格后复检
 * @param inspector 检验员（合成数据）
 */
public record FieldInspectionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String lotNo,
        @NotNull @DecimalMin("0.000") @DecimalMax("100.000")
        @Digits(integer = 3, fraction = 3) BigDecimal purity,
        @NotNull @Pattern(regexp = "FIRST|RETEST") String round,
        @NotBlank @Size(max = 64) String inspector
) {
}
