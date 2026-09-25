package com.example.starter.model;

import java.math.BigDecimal;

/**
 * 检验链项目行。
 *
 * @param itemCode      检验项目编码
 * @param itemName      检验项目名称
 * @param critical      是否关键项目
 * @param limitValue    限值
 * @param measuredValue 测定值
 * @param qualified     是否合格（测定值小于等于限值，恰等限值判合格）
 */
public record InspectionItemRow(
        String itemCode,
        String itemName,
        boolean critical,
        BigDecimal limitValue,
        BigDecimal measuredValue,
        boolean qualified) {
}
