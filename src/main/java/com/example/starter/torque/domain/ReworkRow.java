package com.example.starter.torque.domain;

import java.time.Instant;

/** rework_record 表行记录（返工台账）。 */
public record ReworkRow(
        long id,
        long cardId,
        String pointCode,
        int reworkNo,
        String reason,
        Instant createdAt
) {
}
