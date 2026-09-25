package com.example.starter.dto;

import java.time.LocalDate;

/** 入账结果；SUSPENDED 表示批次已封账、交易挂账。 */
public record PaymentResponse(
        String requestId,
        String channelFlowNo,
        String merchantNo,
        long amount,
        long fee,
        String status,
        LocalDate batchDate,
        boolean replayed) {
}
