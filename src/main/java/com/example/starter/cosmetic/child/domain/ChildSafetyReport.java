package com.example.starter.cosmetic.child.domain;

import java.time.LocalDate;

/**
 * 儿童安全评价报告（合成数据，区别于成人功效评价试验报告）。
 *
 * <p>每个分档需独立的安全报告；成人功效报告不能替代儿童安全报告。
 * 报告必须明确覆盖申请年龄范围（在其分档内的申请年龄段）。</p>
 *
 * @param reportNo            报告编号
 * @param tier                报告对应的分档（每档独立）
 * @param issuedDate          报告出具日（UTC 日界），随受理冻结
 * @param coversFromMonths    报告明确覆盖的年龄下限(整数月，闭区间)
 * @param coversToMonths      报告明确覆盖的年龄上限(整数月，闭区间)
 * @param conclusionSupported 报告结论是否支持该档儿童使用
 * @param conclusionText      报告结论文本
 */
public record ChildSafetyReport(String reportNo,
                                AgeTier tier,
                                LocalDate issuedDate,
                                int coversFromMonths,
                                int coversToMonths,
                                boolean conclusionSupported,
                                String conclusionText) {

    /** 报告声明的覆盖区间是否包含给定 [from, to]（整数月闭区间）。 */
    public boolean covers(int from, int to) {
        return coversFromMonths <= from && coversToMonths >= to;
    }
}
