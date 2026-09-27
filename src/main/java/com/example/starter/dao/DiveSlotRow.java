package com.example.starter.dao;

import java.time.Instant;

/**
 * 潜水员受控下潜占用槽行：每名潜水员至多一行。
 */
public record DiveSlotRow(
        String diverCode,
        String diveRequestId,
        Instant occupiedAtUtc) {
}
