package com.example.starter.cosmetic.child.web.dto;

import com.example.starter.cosmetic.child.domain.ChildReviewDetail;

import java.time.Instant;

/**
 * 儿童分龄审查明细响应（逐条规则，可复算）。
 */
public record ChildReviewDetailResponse(
        String filingNo,
        int versionNo,
        String ruleCode,
        boolean passed,
        String detail,
        Instant createdAtUtc) {

    /** 由领域对象构造。 */
    public static ChildReviewDetailResponse from(ChildReviewDetail d) {
        return new ChildReviewDetailResponse(d.filingNo(), d.versionNo(), d.ruleCode(),
                d.passed(), d.detail(), d.createdAtUtc());
    }
}
