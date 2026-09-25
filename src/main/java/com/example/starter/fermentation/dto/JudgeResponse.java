package com.example.starter.fermentation.dto;

import java.time.Instant;

/**
 * 成熟度综合判定结果：后熟保持 -1℃ 满 7 天才合格；不合格仅允许延长后熟一次。
 */
public record JudgeResponse(
        String requestId,
        String batchNo,
        Instant judgedAt,
        boolean passed,
        boolean extensionGranted,
        String batchStatus,
        String detail) {
}
