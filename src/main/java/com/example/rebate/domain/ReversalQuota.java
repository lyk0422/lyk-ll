package com.example.rebate.domain;

import java.time.Instant;

/**
 * 逐单可冲余额台账行：结案批次内每张报关单一行。
 *
 * @param frozenTaxCents 冻结退税额（整数分）
 * @param occupiedCents 已批准累计占用（整数分）；待复核与驳回不占额
 */
public record ReversalQuota(
        Long id,
        Long batchId,
        String declarationNo,
        Long frozenTaxCents,
        Long occupiedCents,
        Instant createdAt,
        Instant updatedAt) {

    /** 剩余可冲额（整数分），恒非负。 */
    public long remainingCents() {
        return frozenTaxCents - occupiedCents;
    }
}
