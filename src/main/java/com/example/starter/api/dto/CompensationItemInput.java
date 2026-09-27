package com.example.starter.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 补偿批准单项：为配方中每种染料指定批次与系数版本。
 */
public record CompensationItemInput(
        @NotBlank String dyeCode,
        @NotBlank String lotCode,
        @NotNull @Min(1) Integer potencyVersion) {
}
