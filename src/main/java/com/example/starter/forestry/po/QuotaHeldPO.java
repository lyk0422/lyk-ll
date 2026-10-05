package com.example.starter.forestry.po;

import java.math.BigDecimal;

/**
 * 限额档位当前净占用 PO：株数与蓄积双口径。
 */
public record QuotaHeldPO(
        long trees,
        BigDecimal volume
) {
}
