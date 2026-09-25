package com.example.starter.cosmetic.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 功效评价试验报告请求条目（合成报告由调用方登记后随备案提交引用）。
 *
 * @param reportNo            报告编号
 * @param efficacyCode        报告对应功效编码
 * @param issuedDate          报告出具日（UTC 日界日期，例如 2023-06-30）
 * @param conclusionSupported 报告结论是否支持该功效
 * @param conclusionText      报告结论文本
 */
public record TestReportRequest(
        @NotBlank String reportNo,
        @NotBlank String efficacyCode,
        @NotNull LocalDate issuedDate,
        @NotNull Boolean conclusionSupported,
        String conclusionText) {
}
