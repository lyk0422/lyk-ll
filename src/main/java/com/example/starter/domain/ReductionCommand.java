package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 降掺指令。
 */
public record ReductionCommand(
        Long id,
        Long planId,
        LocalDateTime hourStart,
        String pollutant,
        BigDecimal hourAvg,
        CommandStatus status,
        BigDecimal targetRate,
        LocalDateTime triggeredAt,
        LocalDateTime issuedAt,
        LocalDateTime confirmedAt,
        LocalDateTime escalatedAt,
        String confirmRequestId) {
}
