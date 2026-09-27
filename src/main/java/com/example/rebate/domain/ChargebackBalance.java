package com.example.rebate.domain;

import java.time.Instant;

/**
 * 结案后冲回余额：每张已结案批次内报关单一行。
 *
 * @param frozenCents 冻结退税额（整数分）
 * @param occupiedCents 已批准冲回累计占用额（整数分）；剩余可冲额 = frozen - occupied
 */
public record ChargebackBalance(
        Long id,
        Long batchId,
        String declarationNo,
        long frozenCents,
        long occupiedCents,
        Instant createdAt,
        Instant updatedAt) {

    /** 剩余可冲额（整数分）。 */
    public long remainingCents() {
        return frozenCents - occupiedCents;
    }
}
