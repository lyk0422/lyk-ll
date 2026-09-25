package com.example.starter.rebate.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 申报日有效的退税率文库版本。
 *
 * @param hsCode        商品编码
 * @param effectiveDate 命中版本生效日（UTC）
 * @param rate          退税率
 */
public record RateVersionResponse(
        String hsCode,
        LocalDate effectiveDate,
        BigDecimal rate
) {
}
