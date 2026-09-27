package com.example.starter.cosmetic.child.domain;

import java.math.BigDecimal;

/**
 * 儿童分档成分限值（合成规则快照来源）。
 *
 * @param ruleVersion       分龄规则版本
 * @param tier              分档
 * @param ingredientCode    成分编码
 * @param name              成分名称
 * @param maxConcentrationPct 该档允许的最高质量百分比浓度（单位 %），恰等限值合格
 */
public record ChildIngredientLimit(String ruleVersion,
                                   AgeTier tier,
                                   String ingredientCode,
                                   String name,
                                   BigDecimal maxConcentrationPct) {
}
