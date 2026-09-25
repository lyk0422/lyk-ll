package com.example.starter.blast.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 爆破作业申请持久化对象。
 *
 * @param requestId     申请幂等键
 * @param detonateTime 实际起爆备案时刻（UTC），未备案为 NULL
 * @param rejectReason 驳回/作废原因，NULL 表示无
 */
public record BlastApplication(
        Long id,
        String requestId,
        String mineCode,
        String zoneCode,
        String applicant,
        Instant plannedTime,
        BigDecimal chargePerDelayKg,
        ApplicationStatus status,
        Instant detonateTime,
        String rejectReason,
        Instant createdAt,
        Instant updatedAt) {

    public BlastApplication withStatus(ApplicationStatus newStatus, String reason,
                                       Instant newDetonateTime, Instant now) {
        return new BlastApplication(id, requestId, mineCode, zoneCode, applicant, plannedTime,
                chargePerDelayKg, newStatus, newDetonateTime, reason, createdAt, now);
    }
}
