package com.example.starter.wb.domain;

import java.math.BigDecimal;

/**
 * 重心包线顶点（重量 kg，重心 %MAC）。
 */
public record EnvelopePoint(
        int weightKg,
        BigDecimal macPct
) {
}
