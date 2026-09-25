package com.example.starter.cosmetic.web.dto;

import com.example.starter.cosmetic.domain.ReviewDetail;

import java.time.Instant;

/**
 * 审查明细响应（形式/异议审查逐条规则，可复算）。
 */
public record ReviewDetailResponse(
        String filingNo,
        int versionNo,
        String stage,
        String ruleCode,
        boolean passed,
        String detail,
        Instant createdAtUtc) {

    /** 由领域对象构造。 */
    public static ReviewDetailResponse from(ReviewDetail d) {
        return new ReviewDetailResponse(d.filingNo(), d.versionNo(), d.stage(),
                d.ruleCode(), d.passed(), d.detail(), d.createdAtUtc());
    }
}
