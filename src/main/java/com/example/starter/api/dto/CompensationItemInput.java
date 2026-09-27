package com.example.starter.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 补偿审批单项：为一种染料指定批次与系数版本；系数与补偿目标由服务端冻结计算。
 */
public record CompensationItemInput(
        @NotBlank String dyeCode,
        @NotBlank String batchNo,
        @NotNull @Min(1) Integer factorVersion) {
}
