package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 降掺指令视图。
 */
public record CommandResponse(
        Long commandId,
        String planNo,
        LocalDateTime hourStart,
        String pollutant,
        BigDecimal hourAvg,
        String status,
        BigDecimal targetRate,
        LocalDateTime triggeredAt,
        LocalDateTime issuedAt,
        LocalDateTime confirmedAt,
        LocalDateTime escalatedAt) {
}
