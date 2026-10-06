package com.example.starter.trace.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 检测提交响应：检测记录 ID、结论及提交后采收批状态。
 */
public record TestSubmissionResponse(
        Long testId,
        Long chainId,
        Long batchId,
        String conclusion,
        String batchStatus,
        BigDecimal inhibitionRate,
        LocalDateTime createdAt) {
}
