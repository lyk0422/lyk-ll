package com.example.starter.dispatch.repo;

import com.example.starter.dispatch.domain.OrderStatus;

import java.time.Instant;

/**
 * dispatch_order 表行记录。时间字段均为 UTC，空值表示对应事件尚未发生。
 */
public record OrderRow(
        long id,
        String requestId,
        String fingerprint,
        String gateCodes,
        String targetOpenings,
        Instant deadline,
        OrderStatus status,
        Instant issuedAt,
        Instant acceptedAt,
        Instant completedAt,
        Instant timeoutAt,
        Instant cancelledAt,
        Instant abortedAt,
        boolean escalated,
        String abortedDetail,
        long version) {
}
