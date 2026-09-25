package com.example.starter.api;

import com.example.starter.repo.DiscrepancyRow;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 长短款挂账链响应。amount=实际清点数-计划数（正长款/负短款）；
 * dueDate 为销账截止日（发生日+3 个自然日，UTC）；settledAt 非空表示已按期销账。
 */
public record DiscrepancyResponse(
        Long id,
        Long planId,
        long amount,
        String status,
        long responsibleCustodianId,
        String responsibleCustodianCode,
        LocalDate occurredDate,
        LocalDate dueDate,
        Instant settledAt
) {
    public static DiscrepancyResponse of(DiscrepancyRow d, String custodianCode) {
        return new DiscrepancyResponse(
                d.getId(),
                d.getPlanId(),
                d.getAmount(),
                d.getStatus().name(),
                d.getResponsibleCustodianId(),
                custodianCode,
                d.getOccurredDate(),
                d.getDueDate(),
                d.getSettledAt());
    }
}
