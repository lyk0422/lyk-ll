package com.example.starter.penalty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 处罚决定响应。
 */
public record PenaltyResponse(
        long id,
        String requestId,
        long caseId,
        long weighRecordId,
        String plateNo,
        BigDecimal overRate,
        String measure,
        BigDecimal fineAmount,
        int demeritPoints,
        String status,
        Instant servedAt,
        boolean idempotentReplay) {

    static PenaltyResponse of(PenaltyDecision d, boolean idempotentReplay) {
        return new PenaltyResponse(d.id(), d.requestId(), d.caseId(), d.weighRecordId(), d.plateNo(),
                d.overRate(), d.measure(), d.fineAmount(), d.demeritPoints(), d.status(), d.servedAt(),
                idempotentReplay);
    }
}
