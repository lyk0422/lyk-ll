package com.example.starter.pv.web;

import java.time.Instant;
import java.util.List;

/**
 * 监管递交批次结果；批次内容为提交时刻快照，固定不变。
 */
public record BatchResponse(
        String batchNo,
        int caseCount,
        Instant createdAt,
        List<BatchItemView> items) {

    /**
     * 批次明细快照项。
     */
    public record BatchItemView(
            String caseNo,
            int version,
            CaseView caseView) {
    }
}
