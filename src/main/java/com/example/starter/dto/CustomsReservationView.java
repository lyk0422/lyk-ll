package com.example.starter.dto;

import java.time.Instant;

/**
 * 包装子批预占绑定视图。
 */
public record CustomsReservationView(
        String declarationNo,
        String batchNo,
        int declaredQuantity,
        String status,
        int version,
        Instant reservedAt,
        Instant releasedAt,
        Instant writtenOffAt
) {
}
