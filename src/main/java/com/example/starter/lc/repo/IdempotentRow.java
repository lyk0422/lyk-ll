package com.example.starter.lc.repo;

import java.time.Instant;

/**
 * requestId 幂等记录表行对象；httpStatus=0 表示首次处理尚在进行中。
 */
public record IdempotentRow(
        String requestId,
        String fingerprint,
        String operation,
        int httpStatus,
        String resultBody,
        Instant createdAt) {
}
