package com.example.starter.batch;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.example.starter.domain.BatchStatus;

/**
 * 批次响应。时间均为 UTC。
 */
public record BatchResponse(
        long id,
        String requestId,
        String origin,
        String variety,
        BigDecimal quantity,
        BatchStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static BatchResponse from(Batch batch) {
        return new BatchResponse(batch.id(), batch.requestId(), batch.origin(), batch.variety(),
                batch.quantity(), batch.status(), batch.createdAt(), batch.updatedAt());
    }
}
