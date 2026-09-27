package com.example.starter.cosmetic.child.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 儿童分档安全评价报告请求条目（合成报告；成人功效报告不能替代）。
 *
 * @param reportNo            报告编号
 * @param tierCode            报告对应分档：TIER_0_36 / TIER_37_144（每档独立报告）
 * @param issuedDate          报告出具日（UTC 日界）
 * @param coversFromMonths    报告明确覆盖的年龄下限(整数月，闭区间)，0..144
 * @param coversToMonths      报告明确覆盖的年龄上限(整数月，闭区间)，0..144
 * @param conclusionSupported 报告结论是否支持该档儿童使用
 * @param conclusionText      报告结论文本
 */
public record ChildSafetyReportRequest(
        @NotBlank String reportNo,
        @NotBlank String tierCode,
        @NotNull LocalDate issuedDate,
        @Min(0) @Max(144) Integer coversFromMonths,
        @Min(0) @Max(144) Integer coversToMonths,
        @NotNull Boolean conclusionSupported,
        String conclusionText) {
}
