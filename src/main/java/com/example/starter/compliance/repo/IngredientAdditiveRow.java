package com.example.starter.compliance.repo;

import java.math.BigDecimal;

/**
 * 复合配料中添加剂本底含量行；concentration 单位 g/kg 配料。
 */
public record IngredientAdditiveRow(String ingredientCode, String additiveCode, BigDecimal concentration) {
}
