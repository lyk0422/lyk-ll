package com.example.starter.trace.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 加工批次响应，含引用的已放行采收批。
 */
public record ProcessingBatchResponse(
        Long id,
        String batchNo,
        List<Long> harvestBatchIds,
        LocalDateTime createdAt) {
}
