package com.example.rebate.web.dto;

/**
 * 逐单可冲余额视图。
 *
 * @param frozenTaxCents 冻结退税额（整数分）
 * @param occupiedCents 已批准累计占用（整数分）
 * @param remainingCents 剩余可冲额（整数分）
 * @param pendingCents 该单待复核冲回申请合计（整数分，不占额）
 */
public record QuotaBalanceView(
        String batchNo,
        String declarationNo,
        long frozenTaxCents,
        long occupiedCents,
        long remainingCents,
        long pendingCents) {
}
