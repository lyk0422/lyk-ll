package com.example.starter.cosmetic.web.dto;

import com.example.starter.cosmetic.domain.LabelIssue;

import java.time.Instant;

/**
 * 标签领用凭据响应。时间均为 UTC。
 *
 * <p>备案换版/注销后已领凭据仅追加风险关联（riskFlag/riskNote/riskAtUtc），不抹历史。</p>
 */
public record LabelIssueResponse(
        Long issueId,
        String printRunNo,
        String batchNo,
        String productCode,
        int quantity,
        Instant issuedAtUtc,
        boolean riskFlag,
        String riskNote,
        Instant riskAtUtc) {

    /** 由领域记录构造响应。 */
    public static LabelIssueResponse from(LabelIssue i) {
        return new LabelIssueResponse(i.id(), i.printRunNo(), i.batchNo(), i.productCode(),
                i.quantity(), i.issuedAtUtc(), i.riskFlag(), i.riskNote(), i.riskAtUtc());
    }
}
