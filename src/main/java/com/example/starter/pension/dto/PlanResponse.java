package com.example.starter.pension.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.PlanStatus;
import com.example.starter.pension.domain.PayoutReason;

/**
 * 支付计划响应：含申请受理时冻结的权益快照与各期明细。
 */
public record PlanResponse(
        String planNo,
        String requestId,
        String memberNo,
        PayoutReason reason,
        InstallmentType installmentType,
        int installmentCount,
        PlanStatus status,
        long employeeAmountCents,
        long employerVestedCents,
        long employerForfeitCents,
        long totalAmountCents,
        int tenureYears,
        int vestingRateBp,
        LocalDate applicationDate,
        String originalPlanNo,
        Instant settledAtUtc,
        Instant createdAtUtc,
        List<InstallmentResponse> installments) {
}
