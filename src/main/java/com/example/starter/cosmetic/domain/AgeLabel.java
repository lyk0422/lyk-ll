package com.example.starter.cosmetic.domain;

/**
 * 适用年龄标识（每档独立标识；标识适用范围改变必须走实质变更）。
 *
 * @param bandCode               适用年龄档编码
 * @param labelText              标识文本（受理时取其摘要冻结）
 * @param applicableLowerMonths  标识适用范围下限（整数月，闭区间）
 * @param applicableUpperMonths  标识适用范围上限（整数月，闭区间）
 */
public record AgeLabel(String bandCode,
                       String labelText,
                       int applicableLowerMonths,
                       int applicableUpperMonths) {
}
