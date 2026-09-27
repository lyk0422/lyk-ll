package com.example.starter.cosmetic.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 儿童安全报告请求条目（合成报告，随备案/补正登记并冻结清单）。
 *
 * @param reportNo            报告编号
 * @param bandCode            适用年龄档编码（INFANT/CHILD）
 * @param coverageLowerMonths 报告明确覆盖年龄下限（整数月，闭区间，0~144）
 * @param coverageUpperMonths 报告明确覆盖年龄上限（整数月，闭区间，0~144）
 * @param issuedDate          报告出具日（UTC 日界日期）
 * @param conclusionSupported 报告结论是否支持
 * @param conclusionText      报告结论文本
 */
public record ChildrenSafetyReportRequest(
        @NotBlank String reportNo,
        @NotBlank String bandCode,
        @NotNull @Min(0) @Max(144) Integer coverageLowerMonths,
        @NotNull @Min(0) @Max(144) Integer coverageUpperMonths,
        @NotNull java.time.LocalDate issuedDate,
        @NotNull Boolean conclusionSupported,
        String conclusionText) {
}
