package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 见证事件结果。
 */
public record WitnessResult(
        String cardNo,
        int seq,
        String witnessStatus,
        String witnessName,
        String cardStatus,
        Instant eventTime,
        Instant waitingSince,
        Instant escalateAt,
        boolean escalated,
        String note) {
}
