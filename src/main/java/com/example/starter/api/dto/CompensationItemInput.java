package com.example.starter.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 补偿批准单项入参：为配方中每种染料指定批次与系数版本（批准时冻结快照）。
 */
public record CompensationItemInput(
        @NotBlank String dyeCode,
        @NotBlank String batchNo,
        @NotNull @Min(1) Integer factorVersion) {
}
