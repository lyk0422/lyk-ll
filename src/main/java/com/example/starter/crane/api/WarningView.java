package com.example.starter.crane.api;

import java.time.Instant;

/**
 * 越限预警视图，事件不可删除、修正只追加留痕。
 */
public record WarningView(
        Long id,
        String warningCode,
        String craneCode,
        String zoneCode,
        Instant startTs,
        Instant lastTs,
        Instant exitTs,
        String status,
        Long brakeId,
        String brakeCommandCode) {
}
