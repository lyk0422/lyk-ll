package com.example.starter.cosmetic.domain;

import java.math.BigDecimal;

/**
 * 配方成分条目（合成数据）。
 *
 * @param code            成分编码
 * @param name            成分名称
 * @param concentrationPct 配方中质量百分比浓度，单位 %，恰等限用成分限值合法
 */
public record FormulationIngredient(String code, String name, BigDecimal concentrationPct) {
}
