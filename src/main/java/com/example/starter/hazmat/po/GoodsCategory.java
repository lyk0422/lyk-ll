package com.example.starter.hazmat.po;

/**
 * 危化品类别。
 *
 * @param categoryCode  类别编码
 * @param categoryName  类别名称
 * @param requiredLevel 要求的最低路线通行等级（数字越大管制越严）
 */
public record GoodsCategory(String categoryCode, String categoryName, int requiredLevel) {
}
