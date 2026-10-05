package com.example.starter.forestry.po;

import java.math.BigDecimal;

/**
 * 年度采伐限额 PO（按树种 + 年度分档，株数与蓄积双口径）。
 */
public record AnnualQuotaPO(
        Long id,
        String speciesCode,
        int quotaYear,
        Integer totalTrees,
        BigDecimal totalVolume
) {
}
