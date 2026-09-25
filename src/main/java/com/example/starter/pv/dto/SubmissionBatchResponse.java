package com.example.starter.pv.dto;

import java.time.Instant;
import java.util.List;

/**
 * 监管递交批次结果。批次内个例集合为提交时刻快照，重复查询结果稳定。
 */
public record SubmissionBatchResponse(
        String batchNo,
        String requestId,
        Instant createdAt,
        List<Item> items) {

    /**
     * 批次内个例快照项。
     */
    public record Item(
            String caseNo,
            String caseStatus,
            int caseVersion,
            Instant initialSubmittedAt,
            Instant lastFollowUpAt) {
    }
}
