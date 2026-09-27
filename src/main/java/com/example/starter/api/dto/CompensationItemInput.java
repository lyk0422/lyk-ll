package com.example.starter.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 补偿批准明细项：为配方中每种染料指定批次与系数版本（须与批次当前版本一致）。
 */
public record CompensationItemInput(
        @NotBlank String dyeCode,
        @NotBlank String batchNo,
        @NotNull @Min(1) Integer potencyVersion) {
}
