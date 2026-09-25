package com.example.starter.museum.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 借展申请请求。startDate/endDate 为 UTC 日界日期，期限最长 3 年（恰满 3 年合法）。 */
public record LoanApplyRequest(
        @NotBlank String requestId,
        @NotBlank String artifactNo,
        @NotBlank String borrower,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        String showcaseNo
) {
}
