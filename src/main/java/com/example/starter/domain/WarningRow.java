package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 大风预警行。
 */
public record WarningRow(long id, String warningNo, int level, String status,
                         LocalDateTime publishedAt, LocalDateTime cancelledAt) {
}
