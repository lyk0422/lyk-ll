package com.example.rebate.domain;

import java.time.Instant;

/**
 * 结案后迟到单证事件，仅登记不关联。
 */
public record LateEvent(
        Long id,
        Long batchId,
        String declarationNo,
        String invoiceNo,
        String note,
        Instant recordedAt) {
}
