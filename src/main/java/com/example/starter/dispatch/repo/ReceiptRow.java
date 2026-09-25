package com.example.starter.dispatch.repo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * gate_receipt 表行记录：单门执行目标与回执。actualOpening 为空表示尚未回执。
 */
public record ReceiptRow(
        long id,
        long orderId,
        int seqNo,
        String gateCode,
        BigDecimal targetOpening,
        BigDecimal actualOpening,
        Boolean inPosition,
        BigDecimal deviationPct,
        boolean alarmed,
        Instant receivedAt) {
}
