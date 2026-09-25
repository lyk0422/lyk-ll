package com.example.starter.cosmetic.domain;

import java.math.BigDecimal;

/**
 * 备案配方成分明细行，concentration 为质量分数%。
 */
public record IngredientRow(long id, long registrationId, String ingredientCode, BigDecimal concentration) {
}
