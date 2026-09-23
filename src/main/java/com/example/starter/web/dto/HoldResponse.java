package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 法律保全响应：状态 ACTIVE/RELEASED 及当前版本。
 */
public record HoldResponse(
        long holdId,
        String caseId,
        String status,
        long version,
        List<String> evidenceIds,
        String createRequestId,
        String releaseRequestId,
        Instant createdAt,
        Instant releasedAt
) {
}
