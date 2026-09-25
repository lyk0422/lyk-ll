package com.example.starter.wbb.po;

import java.time.LocalDateTime;

/**
 * 双岗复核记录持久化对象。
 */
public record ReviewPo(
        Long id,
        Long manifestId,
        String reviewRole,
        String reviewer,
        String requestId,
        String fingerprint,
        LocalDateTime createdAt
) {
}
