package com.example.starter.pension.dto;

import java.time.Instant;

import com.example.starter.pension.domain.AttemptResult;
import com.example.starter.pension.domain.PlanStatus;

/**
 * 单期支付指令结果。{@code planSettled} 表示该期出款后计划是否已自动结清。
 */
public record PaymentResponse(
        String requestId,
        String planNo,
        int seqNo,
        long amountCents,
        AttemptResult result,
        String detail,
        PlanStatus planStatus,
        boolean planSettled,
        Instant settledAtUtc,
        Instant processedAtUtc) {
}
