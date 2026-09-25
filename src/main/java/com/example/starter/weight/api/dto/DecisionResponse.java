package com.example.starter.weight.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 处罚决定/文书响应。
 *
 * @param decisionId   决定主键
 * @param docNo        文书编号
 * @param caseNo       案件编号
 * @param docType      文书类型
 * @param tier         分档口径快照
 * @param totalKg      判定总重快照（千克）
 * @param limitKg      判定限值快照（千克）
 * @param overloadRate 超限率快照
 * @param fineAmount   罚款金额（元）
 * @param points       记分数
 * @param status       决定状态（ACTIVE/REVOKED）
 * @param servedAt     送达时间（UTC）
 * @param replayed     是否为幂等重放
 */
public record DecisionResponse(
        Long decisionId,
        String docNo,
        String caseNo,
        String docType,
        String tier,
        BigDecimal totalKg,
        BigDecimal limitKg,
        BigDecimal overloadRate,
        BigDecimal fineAmount,
        int points,
        String status,
        Instant servedAt,
        boolean replayed) {
}
