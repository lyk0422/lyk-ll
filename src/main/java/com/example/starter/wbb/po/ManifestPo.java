package com.example.starter.wbb.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 舱单版本持久化对象（字段含义与 manifest 表 COMMENT 一致）。
 */
public record ManifestPo(
        Long id,
        String flightKey,
        String flightNo,
        java.time.LocalDate flightDate,
        String aircraftType,
        String aircraftRegistration,
        Integer versionNo,
        String status,
        Long parentId,
        String requestId,
        String fingerprint,
        BigDecimal fuelKg,
        BigDecimal fuelArm,
        BigDecimal fuelMoment,
        Integer passengerCount,
        BigDecimal payloadKg,
        BigDecimal payloadMoment,
        BigDecimal zeroFuelWeightKg,
        BigDecimal zfwMoment,
        BigDecimal zfwCgArm,
        BigDecimal takeoffWeightKg,
        BigDecimal towMoment,
        BigDecimal towCgArm,
        Integer lmcDeltaPax,
        BigDecimal lmcDeltaKg,
        Boolean frozen,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
