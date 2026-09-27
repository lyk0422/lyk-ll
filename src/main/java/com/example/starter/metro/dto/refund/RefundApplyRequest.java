package com.example.starter.metro.dto.refund;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 封账行程退款申请请求。
 *
 * @param requestId 幂等键
 * @param refundNo  退款单号，全局唯一
 * @param txnNo     原交易流水号（必须已封账且按实结算）
 * @param amountCents 本次退款金额（分），必须为正，且累计退款不得超过原实收
 * @param applyTime 申请精确时刻（Asia/Shanghai）
 */
public record RefundApplyRequest(
        @NotBlank String requestId,
        @NotBlank String refundNo,
        @NotBlank String txnNo,
        @NotNull @Positive Long amountCents,
        @NotNull LocalDateTime applyTime) {
}
