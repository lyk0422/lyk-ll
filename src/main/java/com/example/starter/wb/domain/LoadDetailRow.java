package com.example.starter.wb.domain;

import java.math.BigDecimal;

/**
 * 业载明细行（load_detail）；totalWeightKg = pieces * unitWeightKg。
 */
public record LoadDetailRow(
        Long id,
        long manifestId,
        String category,
        String stationCode,
        int pieces,
        BigDecimal unitWeightKg,
        BigDecimal totalWeightKg,
        int seqNo
) {
}
