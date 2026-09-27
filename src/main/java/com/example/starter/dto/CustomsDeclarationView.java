package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 通关单余量台账视图：总数、预占、已核销、剩余可预占，及撤销状态与版本。
 */
public record CustomsDeclarationView(
        String declarationNo,
        String enterpriseName,
        String productName,
        LocalDate validUntil,
        int totalQuantity,
        int reservedQuantity,
        int writtenOffQuantity,
        int availableQuantity,
        int version,
        boolean revoked,
        Instant revokedAt
) {
}
