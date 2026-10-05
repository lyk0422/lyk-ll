package com.example.starter.forestry.po;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 限额台账明细行 PO。
 */
public record QuotaLedgerEntryPO(
        long id,
        String applicationId,
        String licenseId,
        int trees,
        BigDecimal volume,
        String action,
        LocalDate createdDate
) {
}
