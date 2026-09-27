package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 通关单余量台账视图：总数、预占、已核销、未占余量及全部子批绑定明细。
 */
public record CustomsDeclarationView(
        String declarationNo,
        String enterpriseName,
        String productName,
        Instant validUntil,
        int totalDoses,
        int reservedDoses,
        int consumedDoses,
        int availableDoses,
        String status,
        int version,
        List<CustomsBindingView> bindings
) {
}
