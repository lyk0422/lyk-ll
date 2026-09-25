package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 风压读数行(对应 pressure_reading 表)。
 */
public record PressureReadingRow(long id, long operationId, int pressureKpa,
                                 LocalDateTime occurredAt, boolean late, LocalDateTime createdAt) {
}
