package com.example.starter.casefile;

import java.time.Instant;

/**
 * 案件领域行：一起超限处罚对应一个案件，结案为终态。
 */
public record CaseRecord(
        long id,
        long penaltyId,
        String plateNo,
        String status,
        Instant closedAt) {
}
