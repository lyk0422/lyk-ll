package com.example.starter.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.starter.domain.Permit;
import com.example.starter.domain.PermitStatus;

/**
 * 申请单响应。
 */
public record PermitResponse(
        long id,
        String requestId,
        long zoneId,
        String applicant,
        Instant plannedBlastTime,
        BigDecimal chargePerDelay,
        PermitStatus status,
        String rejectReason) {

    /**
     * 由领域对象构造响应。
     */
    public static PermitResponse from(Permit p) {
        return new PermitResponse(p.id(), p.requestId(), p.zoneId(), p.applicant(),
                p.plannedBlastTime(), p.chargePerDelay(), p.status(), p.rejectReason());
    }
}
