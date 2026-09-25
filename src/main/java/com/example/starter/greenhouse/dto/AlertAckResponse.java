package com.example.starter.greenhouse.dto;

import java.time.Instant;

/**
 * 告警确认结果；同键同参重放原样返回首次结果。
 */
public record AlertAckResponse(
        Long alertId,
        String status,
        Instant acknowledgedAt) {
}
