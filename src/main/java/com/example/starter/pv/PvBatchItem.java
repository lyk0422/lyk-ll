package com.example.starter.pv;

import java.time.Instant;

/**
 * 递交批次个例快照项（对应 pv_submission_batch_item 表）。
 */
public record PvBatchItem(
        Long id,
        long batchId,
        long caseId,
        String caseNo,
        CaseStatus caseStatus,
        int caseVersion,
        Instant initialSubmittedAt,
        Instant lastFollowUpAt) {
}
