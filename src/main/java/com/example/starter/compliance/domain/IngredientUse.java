package com.example.starter.compliance.domain;

import java.math.BigDecimal;

/**
 * 配方使用的复合配料。
 *
 * @param ingredientCode 复合配料目录编码
 * @param ratio          配料占终产品质量比（0~1，无量纲）
 */
public record IngredientUse(String ingredientCode, BigDecimal ratio) {
}
