package com.example.starter.repo;

import java.time.Instant;

/**
 * 押运打卡行。seq 按打卡时间定序（1 为第一人、2 为第二人）。
 */
public record CheckinRow(
        Long id,
        long planId,
        long custodianId,
        int seq,
        Instant checkinTime,
        Instant createdAt
) {
}
