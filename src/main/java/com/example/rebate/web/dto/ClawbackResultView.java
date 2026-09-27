package com.example.rebate.web.dto;

/**
 * 冲回提交/复核结果视图：在冲回单视图外附带批准后额度快照（复核时刻冻结，重放不重算）。
 *
 * @param recoveryId 批准时同事务产生的追缴明细 ID；驳回或待复核时为 {@code null}
 */
public record ClawbackResultView(
        ClawbackView clawback,
        Long recoveryId,
        long frozenTaxCents,
        long occupiedCents,
        long remainingCents,
        int quotaVersion) {
}
