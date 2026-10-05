package com.example.starter.deicing.web.dto;

import com.example.starter.deicing.domain.BorrowStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 借用单视图。
 */
public record BorrowView(
        String requestId,
        String fluidCode,
        String tankCode,
        BigDecimal quantityLiters,
        String fromTeam,
        String toTeam,
        BorrowStatus status,
        Instant createdAt,
        Instant returnedAt) {
}
