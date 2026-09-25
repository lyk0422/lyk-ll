package com.example.starter.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 入账结果。status：POSTED 已入账 / SUSPENDED 已挂账；replayed 表示命中幂等返回首次结果。
 */
public record PaymentResponse(
        String requestId,
        String channelSerialNo,
        String merchantNo,
        BigDecimal amount,
        Instant occurTime,
        String batchDate,
        String status,
        boolean replayed) {
}
