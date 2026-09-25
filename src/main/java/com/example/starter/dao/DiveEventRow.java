package com.example.starter.dao;

import java.time.Instant;

/**
 * 潜次事件行（加压舱预案 / 迟到体征 / 方案冻结）。
 */
public record DiveEventRow(
        Long id,
        String diveRequestId,
        String type,
        String payload,
        Instant createdAtUtc) {
}
