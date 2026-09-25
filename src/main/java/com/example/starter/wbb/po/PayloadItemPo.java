package com.example.starter.wbb.po;

import java.math.BigDecimal;

/**
 * 业载明细持久化对象。
 */
public record PayloadItemPo(
        Long id,
        Long manifestId,
        String category,
        String zoneCode,
        Integer pieceCount,
        BigDecimal weightKg,
        BigDecimal arm,
        BigDecimal moment
) {
}
