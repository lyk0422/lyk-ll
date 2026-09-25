package com.example.rebate.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 退税率文库版本录入请求（合成数据）。
 *
 * @param effectiveFrom UTC 生效日（含当日）
 * @param rebateRate 退税率
 */
public record LibraryRequest(
        @NotBlank String requestId,
        @NotBlank String productCode,
        @NotBlank String versionNo,
        @NotNull LocalDate effectiveFrom,
        @NotNull @DecimalMin("0.0") BigDecimal rebateRate) {
}
