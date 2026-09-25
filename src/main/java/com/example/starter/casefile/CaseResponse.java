package com.example.starter.casefile;

import java.time.Instant;

/**
 * 案件响应：结案为终态，重复结案返回幂等成功。
 */
public record CaseResponse(
        long id,
        Long penaltyId,
        String plateNo,
        String status,
        Instant closedAt,
        boolean idempotentReplay) {
}
