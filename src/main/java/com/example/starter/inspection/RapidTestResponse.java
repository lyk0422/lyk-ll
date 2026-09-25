package com.example.starter.inspection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.TestVerdict;

/**
 * 快检响应。late=true 表示批次已结案，结果仅记录为迟到事件，未产生检测记录。时间均为 UTC。
 */
public record RapidTestResponse(
        Long id,
        long batchId,
        String item,
        BigDecimal inhibitionRate,
        TestVerdict verdict,
        BatchStatus batchStatus,
        boolean late,
        LocalDateTime createdAt) {

    public static RapidTestResponse from(RapidTest test, BatchStatus batchStatus) {
        return new RapidTestResponse(test.id(), test.batchId(), test.item(), test.inhibitionRate(),
                test.verdict(), batchStatus, false, test.createdAt());
    }

    public static RapidTestResponse late(long batchId, BatchStatus batchStatus) {
        return new RapidTestResponse(null, batchId, null, null, null, batchStatus, true, null);
    }
}
