package com.example.starter.weight.repo.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 执法文书行（处罚决定/卸载放行/撤销文书/不超限告知）。
 * 金额单位元，重量单位千克，时间均为 UTC。
 *
 * @param id           主键
 * @param requestId    幂等请求标识
 * @param fingerprint  幂等指纹
 * @param docNo        文书编号
 * @param caseId       关联案件主键
 * @param docType      文书类型（UNLOAD_RELEASE/PENALTY/REVOCATION/NO_ACTION）
 * @param tier         分档口径快照
 * @param totalKg      判定总重快照（千克）
 * @param limitKg      判定限值快照（千克）
 * @param overloadRate 超限率快照
 * @param fineAmount   罚款金额（元）；无罚款为 0
 * @param points       记分数；无记分为 0
 * @param status       决定状态（ACTIVE/REVOKED）
 * @param servedAt     送达时间（UTC）
 * @param revokedAt    撤销时间（UTC）；未撤销为 null
 * @param createdAt    文书生成时间（UTC）
 */
public record DecisionRow(
        Long id,
        String requestId,
        String fingerprint,
        String docNo,
        Long caseId,
        String docType,
        String tier,
        BigDecimal totalKg,
        BigDecimal limitKg,
        BigDecimal overloadRate,
        BigDecimal fineAmount,
        int points,
        String status,
        Instant servedAt,
        Instant revokedAt,
        Instant createdAt) {
}
