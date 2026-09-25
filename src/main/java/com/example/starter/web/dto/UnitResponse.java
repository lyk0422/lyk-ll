package com.example.starter.web.dto;

import com.example.starter.domain.BloodUnit;

import java.time.Instant;

/**
 * 库存单位视图，所有时刻均为 UTC。
 */
public record UnitResponse(
        String barcode,
        String componentType,
        String status,
        String initialResult,
        String repeatResult,
        Instant initialReceivedAt,
        Instant repeatReceivedAt,
        Instant expiresAt,
        Instant expiredAt,
        String scrapReason,
        int version) {

    public static UnitResponse from(BloodUnit unit) {
        return new UnitResponse(
                unit.getBarcode(),
                unit.getComponentType().name(),
                unit.getStatus().name(),
                unit.getInitialResult() == null ? null : unit.getInitialResult().name(),
                unit.getRepeatResult() == null ? null : unit.getRepeatResult().name(),
                unit.getInitialReceivedAt(),
                unit.getRepeatReceivedAt(),
                unit.getExpiresAt(),
                unit.getExpiredAt(),
                unit.getScrapReason(),
                unit.getVersion());
    }
}
