package com.example.starter.cosmetic.domain;

/**
 * 儿童安全报告（合成登记，独立于成人功效评价试验报告）。
 *
 * @param reportNo            报告编号
 * @param bandCode            对应分龄档编码
 * @param coverageLowerMonths 报告明确覆盖的年龄下限（整数月，含）
 * @param coverageUpperMonths 报告明确覆盖的年龄上限（整数月，含）
 * @param conclusionSupported 报告结论是否支持该年龄段使用安全
 * @param conclusionText      报告结论文本
 */
public record ChildSafetyReportRecord(String reportNo,
                                      String bandCode,
                                      int coverageLowerMonths,
                                      int coverageUpperMonths,
                                      boolean conclusionSupported,
                                      String conclusionText) {
}
