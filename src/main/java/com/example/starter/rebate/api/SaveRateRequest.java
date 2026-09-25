package com.example.starter.rebate.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 退税率文库版本录入请求（合成数据）。
 *
 * @param requestId     幂等键
 * @param hsCode        商品编码
 * @param effectiveDate 生效日（UTC 日界）
 * @param rate          退税率（0~1 之间小数）
 */
public record SaveRateRequest(
        @NotBlank String requestId,
        @NotBlank String hsCode,
        @NotNull LocalDate effectiveDate,
        @NotNull @PositiveOrZero BigDecimal rate
) {
}
