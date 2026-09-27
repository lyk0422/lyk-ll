package com.example.starter.cosmetic.child.domain;

import java.math.BigDecimal;

/**
 * 儿童限用成分分档限值（合成规则）。
 *
 * @param ingredientCode      限用成分编码
 * @param bandCode            适用档位编码
 * @param maxConcentrationPct 该档位允许的最高质量百分比浓度（%），恰等限值合格
 */
public record ChildRestrictedLimit(String ingredientCode,
                                   String bandCode,
                                   BigDecimal maxConcentrationPct) {
}
