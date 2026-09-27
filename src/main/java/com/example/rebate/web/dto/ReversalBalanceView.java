package com.example.rebate.web.dto;

/**
 * 逐报关单冲回余额台账视图（金额单位：整数分）。
 *
 * @param frozenCents 冻结退税额（结案明细计税依据严格换算）
 * @param occupiedCents 已批准冲回累计占用额
 * @param availableCents 剩余可冲额=frozen-occupied，恰 0 表示已归零
 * @param receivedCents 追缴累计实际到账额
 * @param versionNo 台账版本，从 1 开始，每次占用/到账成功 +1；尚未发生冲回时虚拟返回 1
 */
public record ReversalBalanceView(
        String batchNo,
        String declarationNo,
        long frozenCents,
        long occupiedCents,
        long availableCents,
        long receivedCents,
        int versionNo) {
}
