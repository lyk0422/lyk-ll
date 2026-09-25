package com.example.starter.forestry.po;

import java.math.BigDecimal;

/**
 * 年度采伐限额 PO（按树种 + 年度分档）。
 */
public record AnnualQuotaPO(
        Long id,
        String speciesCode,
        int quotaYear,
        BigDecimal totalVolume
) {
}
