package com.example.starter.legality.api.dto;

import java.time.Instant;

/**
 * 封板结果；重复封板返回幂等成功。
 */
public record SealResponse(
        String batchId,
        String overallResult,
        boolean sealed,
        Instant finalizedAt,
        boolean idempotentReplay
) {
}
