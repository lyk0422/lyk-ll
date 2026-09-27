package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 通关单余量台账视图：总数 = 已预占 + 已核销 + 未占余量。
 */
public record CustomsDeclarationView(
        String declarationNo,
        String enterpriseName,
        String productName,
        Instant validUntil,
        int totalQuantity,
        int reservedQuantity,
        int consumedQuantity,
        int remainingQuantity,
        boolean revoked,
        Instant revokedAt,
        int version,
        List<BindingView> bindings
) {
}
