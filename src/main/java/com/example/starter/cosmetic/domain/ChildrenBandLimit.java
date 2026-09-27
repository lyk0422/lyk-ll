package com.example.starter.cosmetic.domain;

import java.math.BigDecimal;

/**
 * 儿童分档限用成分限值。
 *
 * @param bandCode            年龄档编码
 * @param ingredientCode      限用成分编码
 * @param maxConcentrationPct 该档允许的最高质量百分比浓度（单位 %），恰等限值合法
 */
public record ChildrenBandLimit(String bandCode,
                                String ingredientCode,
                                BigDecimal maxConcentrationPct) {
}
