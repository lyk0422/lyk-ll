package com.example.starter.cosmetic.domain;

import java.time.LocalDate;

/**
 * 儿童安全报告（合成数据，独立报告类型；成人功效评价报告不能替代）。
 *
 * @param reportNo            报告编号
 * @param bandCode            适用年龄档编码
 * @param coverageLowerMonths 报告明确覆盖年龄下限（整数月，闭区间）
 * @param coverageUpperMonths 报告明确覆盖年龄上限（整数月，闭区间）
 * @param issuedDate          报告出具日（UTC 日界）
 * @param conclusionSupported 报告结论是否支持
 * @param conclusionText      报告结论文本
 */
public record ChildrenSafetyReport(String reportNo,
                                   String bandCode,
                                   int coverageLowerMonths,
                                   int coverageUpperMonths,
                                   LocalDate issuedDate,
                                   boolean conclusionSupported,
                                   String conclusionText) {
}
