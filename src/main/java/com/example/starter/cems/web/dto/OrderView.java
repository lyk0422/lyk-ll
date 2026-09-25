package com.example.starter.cems.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 限排令视图。status 为 ISSUED/ESCALATED/CLOSED；escalated 表示恰满 2 小时未确认而升级并联动限产。
 */
public record OrderView(
        Long id,
        String outfallCode,
        String requestId,
        String triggerSlots,
        String status,
        LocalDateTime issuedAt,
        LocalDateTime confirmDeadline,
        boolean confirmed,
        LocalDateTime confirmedAt,
        boolean escalated,
        LocalDateTime escalatedAt,
        boolean productionLimited,
        BigDecimal baselineTotal,
        LocalDateTime baselineFrozenAt,
        LocalDateTime retestSlot,
        BigDecimal retestValue,
        String releaseRequestId,
        LocalDateTime releasedAt) {
}
