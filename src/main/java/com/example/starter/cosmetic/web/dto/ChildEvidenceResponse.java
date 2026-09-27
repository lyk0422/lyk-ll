package com.example.starter.cosmetic.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 儿童备案判定证据响应：受理冻结的报告清单、分龄规则版本、标识文本摘要，
 * 以及该修订的逐条审查明细（可复算）。
 *
 * @param filingNo        备案编号
 * @param versionNo       修订版本号
 * @param status          该修订状态
 * @param ageLowerMonths  申请年龄下限（整数月，含）
 * @param ageUpperMonths  申请年龄上限（整数月，含）
 * @param ageRuleVersion  受理时冻结的分龄规则版本
 * @param frozenDossiers  受理时冻结的分龄档材料清单（安全报告+适用年龄标识）
 * @param labelDigest     受理时冻结的标识文本摘要（SHA-256）
 * @param frozenAtUtc     受理冻结时刻（UTC）
 * @param reviewDetails   分龄审查逐条明细
 */
public record ChildEvidenceResponse(
        String filingNo,
        int versionNo,
        String status,
        Integer ageLowerMonths,
        Integer ageUpperMonths,
        String ageRuleVersion,
        List<FilingResponse.ChildDossierView> frozenDossiers,
        String labelDigest,
        Instant frozenAtUtc,
        List<ReviewDetailResponse> reviewDetails) {
}
