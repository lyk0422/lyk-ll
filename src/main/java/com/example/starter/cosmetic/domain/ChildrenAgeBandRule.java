package com.example.starter.cosmetic.domain;

/**
 * 儿童分龄规则档位（合成规则）。
 *
 * @param bandCode    年龄档编码：INFANT=0~36月，CHILD=37~144月
 * @param lowerMonths 档位下限（整数月，闭区间）
 * @param upperMonths 档位上限（整数月，闭区间）
 * @param ruleVersion 分龄规则版本，受理时随备案冻结
 */
public record ChildrenAgeBandRule(String bandCode,
                                  int lowerMonths,
                                  int upperMonths,
                                  int ruleVersion) {
}
