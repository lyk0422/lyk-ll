package com.example.starter.dto;

import java.time.Instant;

/**
 * 日结封存响应。replayed=true 表示对已封存指令重复封存（幂等成功）。
 */
public record SettleResponse(
        Long orderId,
        String orderNo,
        String status,
        Instant settledAt,
        boolean replayed) {
}
