package com.example.rebate.domain;

import java.time.Instant;

/**
 * 冲回可退余额台账（逐报关单一行，懒创建）。
 *
 * <p>冲回只占用本行额度，不改变原文库版本、核销流水与批次结案总额。</p>
 *
 * @param frozenCents 冻结退税额（正数整数分），由结案明细 tax_basis(元)×100 严格换算
 * @param occupiedCents 已批准冲回累计占用额（整数分）；可冲余额=frozen-occupied
 * @param receivedCents 追缴回执累计实际到账额（整数分）；不得超过 occupied
 * @param versionNo 台账版本，从 1 开始，每次占用/到账成功 +1
 */
public record ReversalLedger(
        Long id,
        Long batchId,
        String declarationNo,
        long frozenCents,
        long occupiedCents,
        long receivedCents,
        int versionNo,
        Instant createdAt,
        Instant updatedAt) {
}
