package com.example.starter.pension.api;

import com.example.starter.pension.domain.ClaimStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 继承申领响应，含台账勾稽明细（可复算：构成条目合计=继承支付额=出款条目）。
 *
 * @param claimNo                   继承申领号
 * @param requestId                 申领幂等键
 * @param memberNo                  身故成员业务编号
 * @param heirName                  继承人姓名
 * @param deathDate                 成员身故日（UTC 日历日）
 * @param applyDate                 申领受理日（UTC 日界）
 * @param accountBalanceCents       账户余额构成，单位：分
 * @param unpaidInstallmentCents    未支付分期本金构成，单位：分
 * @param claimAmountCents          继承支付额，单位：分
 * @param status                    申领状态
 * @param terminatedPlanNo          被终止的支付计划号；null 表示受理时无进行中计划
 * @param replayed                  本次是否为幂等重放（同 requestId 同参返回首次结果）
 * @param paidAt                    继承支付完成时间（UTC）；未完成为 null
 * @param ledger                    台账勾稽明细
 */
public record InheritanceClaimResponse(
        String claimNo,
        String requestId,
        String memberNo,
        String heirName,
        LocalDate deathDate,
        LocalDate applyDate,
        long accountBalanceCents,
        long unpaidInstallmentCents,
        long claimAmountCents,
        ClaimStatus status,
        String terminatedPlanNo,
        boolean replayed,
        OffsetDateTime paidAt,
        List<LedgerEntry> ledger) {

    /**
     * 台账勾稽明细条目。
     *
     * @param entryType      台账类型：ACCOUNT_BALANCE / UNPAID_INSTALLMENT / INHERITANCE_PAYOUT
     * @param installmentId  引用的期次 ID；非期次类条目为 null
     * @param amountCents    台账金额，单位：分
     */
    public record LedgerEntry(String entryType, Long installmentId, long amountCents) {
    }
}
