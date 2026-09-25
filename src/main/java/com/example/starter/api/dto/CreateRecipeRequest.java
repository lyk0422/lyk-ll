package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 创建配方草稿请求；首个版本号为 1。
 */
public record CreateRecipeRequest(
        @NotBlank String recipeCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal liquorRatio,
        @NotEmpty @Valid List<RecipeItemInput> items) {
}
