package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 公示记录视图（公示链，可复算 7 个自然日期满）。
 */
public record PublicityView(
        LocalDate startUtcDate,
        LocalDate endUtcDate,
        String status,
        Instant objectionAt,
        Instant effectiveAt
) {
}
