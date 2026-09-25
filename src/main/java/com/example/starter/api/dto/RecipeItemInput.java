package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 配方单项入参：染化料编码与浓度(g/L)。
 */
public record RecipeItemInput(
        @NotBlank String dyeCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal dosageGpl) {
}
