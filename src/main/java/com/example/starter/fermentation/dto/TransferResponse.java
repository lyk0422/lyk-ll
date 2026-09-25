package com.example.starter.fermentation.dto;

import java.time.Instant;

/**
 * 清酒转出结果：批次进入 TRANSFERRED 终态；重复转出原样返回首次结果。
 */
public record TransferResponse(
        String requestId,
        String batchNo,
        Instant transferredAt,
        String batchStatus,
        boolean replayed) {
}
