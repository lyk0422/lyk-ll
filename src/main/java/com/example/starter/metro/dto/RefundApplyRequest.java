package com.example.starter.metro.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 退款申请请求：仅可引用已封账且已按实结算的行程。
 *
 * @param requestId   幂等键
 * @param refundNo    退款单号，全局唯一
 * @param txnNo       原交易流水号
 * @param amountCents 本次退款金额（分），正整数；同行程累计不得超过原实收
 * @param applyTime   申请精确时刻（Asia/Shanghai）
 */
public record RefundApplyRequest(
        @NotBlank String requestId,
        @NotBlank String refundNo,
        @NotBlank String txnNo,
        @Positive @NotNull Long amountCents,
        @NotNull LocalDateTime applyTime) {
}
