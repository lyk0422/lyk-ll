package com.example.starter.margin.api;

import com.example.starter.margin.domain.DepositResult;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 追加保证金入账响应。
 *
 * @param requestId        幂等键
 * @param accountNo        账户号
 * @param amount           入账金额（元）
 * @param currency         币种
 * @param result           入账结果（ACCEPTED/REPLAYED/APPLIED_TO_REMAINING/REJECTED_CLOSED）
 * @param replayed         是否为重放（同键同参返回首次结果）
 * @param ratioAfter       入账后维持担保比例（如 1.4200）
 * @param noticeResolved   是否解除了未结清追保通知
 * @param jobCancelled     是否撤销了待发强平作业
 * @param processedAt      处理时间（UTC）
 */
public record DepositResponse(
        String requestId,
        String accountNo,
        BigDecimal amount,
        String currency,
        DepositResult result,
        boolean replayed,
        BigDecimal ratioAfter,
        boolean noticeResolved,
        boolean jobCancelled,
        Instant processedAt
) {
}
