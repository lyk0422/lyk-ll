package com.example.starter.dispatch.repo;

import java.math.BigDecimal;

/**
 * gate_step 表行记录。未回执字段为 null。
 */
public record GateStepRow(
        long id,
        String requestId,
        int seq,
        String gateCode,
        BigDecimal targetOpening,
        BigDecimal actualOpening,
        BigDecimal deviation,
        Boolean inPosition,
        Long completedAt,
        String status,
        String receiptFingerprint) {
}
