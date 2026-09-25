package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 排缸请求：按已发布冻结配方版本开立缸次，并唯一占用染缸。
 */
public record ScheduleLotRequest(
        @NotBlank String requestId,
        @NotBlank String lotNo,
        @NotBlank String vatNo,
        @NotBlank String recipeCode,
        @NotNull @Min(1) Integer recipeVersion,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal fabricWeightKg) {
}
