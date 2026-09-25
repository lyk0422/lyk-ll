package com.example.starter.museum.api;

import com.example.starter.museum.domain.ContractStatus;

import java.time.Instant;
import java.time.LocalDate;

/** 合同视图。 */
public record ContractResponse(
        String contractNo,
        String artifactNo,
        String borrower,
        LocalDate startDate,
        LocalDate endDate,
        ContractStatus status,
        String showcaseNo,
        int renewCount,
        Instant suspendedAt,
        Instant resumedAt,
        Instant returnedAt,
        Instant closedAt,
        int version
) {
}
