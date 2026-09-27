package com.example.starter.metro.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 封账后退款申请请求。
 *
 * @param requestId   幂等键
 * @param txnNo       原交易流水号（须已封账且已按实结算）
 * @param amountCents 本次退款金额（分），必须为正；累计退款不得超过原实收
 * @param reason      退款原因（合法文本可包含任意分隔符）
 * @param applicant   申请人
 */
public record RefundApplyRequest(
        @NotBlank String requestId,
        @NotBlank String txnNo,
        @Positive long amountCents,
        @NotBlank String reason,
        @NotBlank String applicant) {
}
