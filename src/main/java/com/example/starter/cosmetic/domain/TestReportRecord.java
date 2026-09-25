package com.example.starter.cosmetic.domain;

import java.time.LocalDate;

/**
 * 功效评价试验报告（合成数据）。
 *
 * @param reportNo           报告编号
 * @param efficacyCode       报告对应的功效编码
 * @param issuedDate         报告出具日（UTC 日界）；备案提交日前 3 年内有效，恰满 3 年当日有效
 * @param conclusionSupported 报告结论是否支持该功效宣称（宣称与报告结论须一致）
 * @param conclusionText     报告结论文本
 */
public record TestReportRecord(String reportNo,
                               String efficacyCode,
                               LocalDate issuedDate,
                               boolean conclusionSupported,
                               String conclusionText) {
}
