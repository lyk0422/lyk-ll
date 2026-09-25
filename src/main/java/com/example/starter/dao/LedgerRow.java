package com.example.starter.dao;

import java.time.Instant;

/**
 * 潜水员累计水下时间台账行。
 */
public record LedgerRow(
        Long id,
        String diverCode,
        String diveRequestId,
        int planVersion,
        int bottomMin,
        Instant startedAtUtc) {
}
