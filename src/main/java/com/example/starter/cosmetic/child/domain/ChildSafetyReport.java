package com.example.starter.cosmetic.child.domain;

/**
 * 儿童安全报告（合成数据，按档位独立）。
 *
 * <p>成人功效评价报告（test_report）不属于儿童安全报告，不能替代本类资料。</p>
 *
 * @param reportNo        报告编号
 * @param bandCode        报告所属档位编码
 * @param coverLowerMonths 报告明确覆盖的年龄下限（整数月，含）
 * @param coverUpperMonths 报告明确覆盖的年龄上限（整数月，含）
 * @param conclusionText  报告结论摘要文本
 */
public record ChildSafetyReport(String reportNo,
                                String bandCode,
                                int coverLowerMonths,
                                int coverUpperMonths,
                                String conclusionText) {
}
