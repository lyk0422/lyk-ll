package com.example.starter.forestry.po;

import java.math.BigDecimal;

/**
 * 年度限额档位 PO：株数与蓄积双口径总额。
 */
public record QuotaTierPO(
        int totalTrees,
        BigDecimal totalVolume
) {
}
