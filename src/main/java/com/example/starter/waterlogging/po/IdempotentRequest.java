package com.example.starter.waterlogging.po;

import java.time.LocalDateTime;

/**
 * requestId 幂等台账 idempotent_request 映射，response_body 存放首次响应 JSON。
 */
public record IdempotentRequest(
        Long id,
        String requestId,
        String requestType,
        String fingerprint,
        Integer responseStatus,
        String responseBody,
        LocalDateTime createdAt) {
}
