package com.example.starter.torque.dto;

import java.time.Instant;

/**
 * 放行归档结果；终态重复归档幂等成功。
 */
public record ReleaseResult(
        String cardNo,
        String status,
        boolean idempotentReplay,
        Instant releasedAt
) {
}
