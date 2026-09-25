package com.example.starter.wbb.po;

import java.time.LocalDateTime;

/**
 * requestId 幂等记录持久化对象。
 */
public record IdempotentRequestPo(
        String requestId,
        String bizType,
        String fingerprint,
        Long manifestId,
        String outcome,
        LocalDateTime createdAt
) {
}
