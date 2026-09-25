package com.example.starter.recheck;

import java.time.LocalDateTime;

import com.example.starter.domain.RecheckResult;

/**
 * 定量复检记录。时间均为 UTC。
 */
public record Recheck(
        long id,
        String requestId,
        String fingerprint,
        long batchId,
        RecheckResult result,
        LocalDateTime createdAt) {
}
