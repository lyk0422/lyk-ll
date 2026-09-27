package com.example.rebate.domain;

import java.time.Instant;

/**
 * 逐单（批次＋报关单）可冲额度。
 *
 * @param frozenTaxCents 冻结退税额快照，单位：分，不随后续冲回改变
 * @param occupiedCents 已批准冲回累计占用额，单位：分；驳回与待复核不占额
 * @param versionNo 乐观版本号，从 1 开始，每次成功占用加 1
 */
public record ClawbackQuota(
        long batchId,
        String declarationNo,
        long frozenTaxCents,
        long occupiedCents,
        int versionNo,
        Instant createdAt,
        Instant updatedAt) {

    /** 剩余可冲额，单位：分；恰归零合法。 */
    public long remainingCents() {
        return frozenTaxCents - occupiedCents;
    }
}
