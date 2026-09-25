package com.example.starter.quarantine.api.dto;

import com.example.starter.quarantine.domain.BlockadeStatus;
import java.time.Instant;

/**
 * 封锁发布结果，含三级联动影响统计。
 *
 * @param rejectedPendingCount 未出证申报被驳回数量
 * @param voidedCertifiedCount 已出证未启运被自动作废的证数量
 * @param flaggedInTransitCount 已在途被强制隔离观察标记数量
 */
public record BlockadeView(
        String blockadeId,
        String farmId,
        BlockadeStatus status,
        Instant effectiveAt,
        String reason,
        int rejectedPendingCount,
        int voidedCertifiedCount,
        int flaggedInTransitCount,
        boolean idempotentReplay) {
}
