package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 爆破作业申请单。时间均为 UTC；chargePerDelay 单位 kg。
 */
public record Permit(
        long id,
        String requestId,
        String fingerprint,
        long zoneId,
        String applicant,
        Instant plannedBlastTime,
        BigDecimal chargePerDelay,
        PermitStatus status,
        String rejectReason) {
}
