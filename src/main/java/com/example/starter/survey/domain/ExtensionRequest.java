package com.example.starter.survey.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 年度检验展期申请；年度检验至多展期一次、最长 3 个月（含恰满 3 个月）。
 *
 * @param id           主键
 * @param requestId    展期申请幂等键
 * @param surveyId     关联检验
 * @param certificateId 关联的原有效证书（可为 null：先展期后发证场景下记录为 null）
 * @param months       申请展期月数（1..3）
 * @param status       PENDING / APPROVED / REJECTED
 * @param oldValidUntil 批准前证书有效期至（UTC）
 * @param newValidUntil 批准后证书有效期至（UTC），未批准为 null
 * @param appliedAt    申请时刻（UTC）
 * @param decidedAt    批准/驳回时刻（UTC）
 * @param note         驳回原因等说明
 * @param fingerprint  业务指纹
 */
public record ExtensionRequest(
        Long id,
        String requestId,
        Long surveyId,
        Long certificateId,
        int months,
        ExtensionStatus status,
        LocalDate oldValidUntil,
        LocalDate newValidUntil,
        Instant appliedAt,
        Instant decidedAt,
        String note,
        String fingerprint
) {
}
