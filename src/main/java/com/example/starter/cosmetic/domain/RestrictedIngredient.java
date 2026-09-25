package com.example.starter.cosmetic.domain;

import java.math.BigDecimal;

/**
 * 限用成分限值条目。
 *
 * @param ingredientCode    成分编码
 * @param name              成分名称
 * @param maxConcentrationPct 配方中允许的最高质量百分比浓度（单位 %），恰等限值合法
 */
public record RestrictedIngredient(String ingredientCode, String name, BigDecimal maxConcentrationPct) {
}
