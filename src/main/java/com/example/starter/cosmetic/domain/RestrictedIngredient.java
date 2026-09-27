package com.example.starter.cosmetic.domain;

import java.math.BigDecimal;

/**
 * 限用成分限值条目。
 *
 * @param ingredientCode          成分编码
 * @param name                    成分名称
 * @param maxConcentrationPct     配方中允许的最高质量百分比浓度（成人，单位 %），恰等限值合法
 * @param childMaxBand036Pct      儿童 0~36 月档最高质量百分比浓度（单位 %），恰等合法；null=该档不设限
 * @param childMaxBand37144Pct    儿童 37~144 月档最高质量百分比浓度（单位 %），恰等合法；null=该档不设限
 */
public record RestrictedIngredient(String ingredientCode,
                                   String name,
                                   BigDecimal maxConcentrationPct,
                                   BigDecimal childMaxBand036Pct,
                                   BigDecimal childMaxBand37144Pct) {
}
