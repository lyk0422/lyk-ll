package com.example.starter.cosmetic.api;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 功效评价试验报告载荷；issuedDate 为 UTC 日界，conclusion 取 SUPPORT/NOT_SUPPORT。
 */
public record ReportPayload(
        @NotBlank(message = "报告对应功效编码必填") String efficacyCode,
        @NotBlank(message = "报告结论必填") String conclusion,
        @NotNull(message = "报告出具日期必填") LocalDate issuedDate,
        @NotBlank(message = "出具机构必填") String lab) {
}
