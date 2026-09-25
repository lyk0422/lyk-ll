package com.example.rebate.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 进项发票（合成数据），按 报关单号+发票号 去重。
 */
public record InputInvoice(
        Long id,
        String invoiceNo,
        String declarationNo,
        String productCode,
        BigDecimal amount,
        Instant collectedAt) {
}
