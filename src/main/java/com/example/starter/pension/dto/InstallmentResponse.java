package com.example.starter.pension.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.example.starter.pension.domain.InstallmentStatus;

/**
 * 计划单期明细。
 */
public record InstallmentResponse(
        int seqNo,
        LocalDate dueDate,
        long amountCents,
        InstallmentStatus status,
        Instant paidAtUtc,
        String paidRequestId) {
}
