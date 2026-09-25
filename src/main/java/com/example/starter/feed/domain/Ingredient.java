package com.example.starter.feed.domain;

import java.math.BigDecimal;

/**
 * 原料/药物添加剂目录项（合成数据）。
 *
 * @param code           目录编码
 * @param name           名称
 * @param medicated      是否药物添加剂
 * @param maxRatio       最大添加比例（0~1，恰等于合法）；非药物为 null 表示不限
 * @param withdrawalDays 休药天数（UTC 整数日界）；非药物为 null
 */
public record Ingredient(
        String code,
        String name,
        boolean medicated,
        BigDecimal maxRatio,
        Integer withdrawalDays) {
}
