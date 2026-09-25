package com.example.starter.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 调账结果；fromBatchDate 为已封账批次，toBatchDate 为入账的开放批次。
 */
public record AdjustResponse(
        String channelSerialNo,
        String fromBatchDate,
        String toBatchDate,
        BigDecimal amount,
        Instant adjustedAt) {
}
