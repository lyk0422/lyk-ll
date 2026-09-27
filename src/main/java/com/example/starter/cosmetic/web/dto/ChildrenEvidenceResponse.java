package com.example.starter.cosmetic.web.dto;

import com.example.starter.cosmetic.domain.AgeLabel;
import com.example.starter.cosmetic.domain.ChildrenSafetyReport;

import java.util.List;

/**
 * 儿童备案判定证据响应：受理冻结的报告清单、分龄规则版本、标识文本摘要
 * 及逐条审查明细，按版本/修订逐条列示，供复算。
 */
public record ChildrenEvidenceResponse(
        String filingNo,
        String productCode,
        List<RevisionEvidence> revisions) {

    /** 单个版本/修订的冻结证据。 */
    public record RevisionEvidence(
            int versionNo,
            int revisionNo,
            String status,
            String changeType,
            int ruleVersion,
            String labelDigest,
            List<ChildrenSafetyReport> safetyReports,
            List<AgeLabel> labels,
            List<ReviewDetailResponse> reviewDetails) {
    }
}
