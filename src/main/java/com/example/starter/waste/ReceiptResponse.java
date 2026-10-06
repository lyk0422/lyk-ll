package com.example.starter.waste;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回执响应。偏差超过 2% 时 exceptionId 非空且联单转 EXCEPTION。
 */
public record ReceiptResponse(
        Long receiptId,
        Long manifestId,
        String requestId,
        BigDecimal receivedNetKg,
        BigDecimal deviationRatio,
        String manifestStatus,
        Long exceptionId,
        LocalDateTime receivedAt) {
}
