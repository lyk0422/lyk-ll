package com.example.starter.weight.api.dto;

import java.time.Instant;

/**
 * 复检申诉/结论响应。
 *
 * @param reviewId    复检申请主键
 * @param caseNo      案件编号
 * @param decisionId  被复检的处罚决定主键
 * @param status      复检状态（UNDER_REVIEW/UPHELD/OVERTURNED）
 * @param appliedAt   申请时间（UTC）
 * @param concludedAt 结论时间（UTC）；未结论为 null
 * @param replayed    是否为幂等重放
 */
public record ReviewResponse(
        Long reviewId,
        String caseNo,
        Long decisionId,
        String status,
        Instant appliedAt,
        Instant concludedAt,
        boolean replayed) {
}
