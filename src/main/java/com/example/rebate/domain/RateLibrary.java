package com.example.rebate.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 退税率文库版本（合成数据）。
 *
 * @param productCode 商品编码
 * @param versionNo 文库版本号
 * @param effectiveFrom UTC 生效日（含当日）
 * @param rebateRate 退税率
 */
public record RateLibrary(
        Long id,
        String productCode,
        String versionNo,
        LocalDate effectiveFrom,
        BigDecimal rebateRate) {
}
