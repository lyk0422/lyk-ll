package com.example.starter.pension.po;

/**
 * inheritance_recon 表行对象（勾稽明细，可回源表复算）。
 *
 * @param id          主键
 * @param claimId     所属继承申领 ID
 * @param component   勾稽组成：ACCOUNT_BALANCE / UNPAID_INSTALLMENT
 * @param sourceType  来源类型：CONTRIBUTION / INSTALLMENT
 * @param sourceId    来源行主键
 * @param amountCents 计入继承金额的金额，单位：分
 */
public record InheritanceReconRow(
        long id,
        long claimId,
        String component,
        String sourceType,
        long sourceId,
        long amountCents) {
}
