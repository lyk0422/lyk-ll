package com.example.starter.feed.domain;

import java.math.BigDecimal;

/**
 * 配方版本组成项快照。
 *
 * @param ingredientCode 原料/药物编码
 * @param ratio          添加比例（0~1）
 */
public record FormulaItem(String ingredientCode, BigDecimal ratio) {
}
