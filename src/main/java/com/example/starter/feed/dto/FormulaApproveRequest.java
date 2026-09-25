package com.example.starter.feed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 配方批准请求：仅可批准合规校验通过（SUBMITTED）的版本；批准即冻结。
 *
 * @param formulaCode 配方编码
 * @param version     待批准版本号
 */
public record FormulaApproveRequest(
        @NotBlank(message = "配方编码不能为空")
        @Size(max = 64)
        String formulaCode,

        @Positive(message = "版本号必须为正数")
        int version
) {
}
