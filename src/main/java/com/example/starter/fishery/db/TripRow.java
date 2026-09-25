package com.example.starter.fishery.db;

import com.example.starter.fishery.domain.TripStatus;

import java.time.Instant;

/**
 * 航次台账行。
 *
 * @param status 航次状态
 * @param frozen 复核期间该船配额余额是否冻结（冻结由该航次复核案件触发）
 */
public record TripRow(
        Long id,
        String tripCode,
        String vesselCode,
        Instant departAt,
        Instant landedAt,
        TripStatus status,
        boolean frozen,
        Instant createdAt,
        Instant updatedAt) {
}
