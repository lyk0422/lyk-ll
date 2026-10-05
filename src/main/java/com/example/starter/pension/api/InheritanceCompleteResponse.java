package com.example.starter.pension.api;

import com.example.starter.pension.domain.InheritanceStatus;

import java.time.OffsetDateTime;

/**
 * 继承支付完成结果。完成为终态，重复完成返回幂等成功（replayed=true）。
 *
 * @param claimNo                继承申领号
 * @param status                 完成后状态（COMPLETED）
 * @param replayed               true 表示对已完成申领的重复完成（幂等成功）
 * @param inheritanceAmountCents 继承支付额，单位：分
 * @param paidTotalCents         继承台账累计出款总额，单位：分
 * @param completedAt            完成时间（UTC）
 */
public record InheritanceCompleteResponse(
        String claimNo,
        InheritanceStatus status,
        boolean replayed,
        long inheritanceAmountCents,
        long paidTotalCents,
        OffsetDateTime completedAt) {
}
