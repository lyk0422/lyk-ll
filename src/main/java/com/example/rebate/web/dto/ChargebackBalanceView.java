package com.example.rebate.web.dto;

/**
 * 逐单冲回余额视图（每张已结案批次内报关单一行）。
 *
 * @param frozenCents 冻结退税额（整数分）
 * @param occupiedCents 已批准冲回累计占用（整数分）
 * @param remainingCents 剩余可冲额（整数分）
 */
public record ChargebackBalanceView(
        String batchNo,
        String declarationNo,
        long frozenCents,
        long occupiedCents,
        long remainingCents) {
}
