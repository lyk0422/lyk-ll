package com.example.starter.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 对账差异视图。
 */
public record DiffView(
        Long id,
        String batchDate,
        String channelSerialNo,
        String merchantNo,
        String diffType,
        BigDecimal localAmount,
        BigDecimal channelAmount,
        String status,
        String resolveAction,
        String resolveRemark,
        Instant resolvedAt) {
}
