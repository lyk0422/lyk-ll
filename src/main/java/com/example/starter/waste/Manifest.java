package com.example.starter.waste;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收运联单实体行。
 */
public record Manifest(
        long id,
        String requestId,
        String fingerprint,
        long vehicleId,
        String community,
        String category,
        long facilityId,
        BigDecimal grossKg,
        BigDecimal tareKg,
        BigDecimal netKg,
        ManifestStatus status,
        LocalDateTime createdAt) {
}
