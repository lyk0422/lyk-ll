package com.example.starter.pension.po;

import java.time.OffsetDateTime;

/**
 * inheritance_ledger 表行对象（继承台账勾稽明细）。
 *
 * @param id          主键
 * @param claimId     所属继承申领 ID
 * @param entryType   台账类型：ACCOUNT_BALANCE / UNPAID_INSTALLMENT / INHERITANCE_PAYOUT
 * @param refId       引用的期次 ID；0 表示非期次类条目
 * @param amountCents 台账金额，单位：分
 * @param createdAt   入账时间（UTC）
 */
public record InheritanceLedgerRow(
        long id,
        long claimId,
        String entryType,
        long refId,
        long amountCents,
        OffsetDateTime createdAt) {
}
