package com.example.starter.water;

import java.time.Instant;

/**
 * 处置指令行记录；confirmDeadline = issuedAt + 5 分钟，恰满未确认即升级；
 * confirmedAt/escalatedAt 依状态为空。
 */
public record DisposalOrderRow(
        long id,
        long alarmId,
        long tankId,
        DisposalType type,
        DisposalStatus status,
        Instant issuedAt,
        Instant confirmDeadline,
        Instant confirmedAt,
        Instant escalatedAt) {
}
