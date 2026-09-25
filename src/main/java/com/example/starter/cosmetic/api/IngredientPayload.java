package com.example.starter.cosmetic.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 配方成分载荷，concentration 为质量分数%。
 */
public record IngredientPayload(
        @NotBlank(message = "成分编码必填") String ingredientCode,
        @NotNull(message = "成分浓度必填") BigDecimal concentration) {
}
