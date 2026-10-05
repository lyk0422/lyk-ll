package com.example.starter.feed.dto;

import java.time.Instant;

/**
 * 出栏申请台账视图（含自动撤销记录，保留可查）。
 *
 * @param id            申请主键
 * @param requestId     申请幂等键
 * @param applyAtUtc    申请出栏时刻（UTC）
 * @param approved      是否批准
 * @param status        SCHEDULED / EXECUTED / REJECTED / REVOKED
 * @param rejectReason  拒绝原因；批准为 null
 * @param revokedAtUtc  自动撤销时刻（UTC）；未撤销为 null
 * @param revokeReason  自动撤销原因；未撤销为 null
 */
public record SlaughterApplicationView(
        long id,
        String requestId,
        Instant applyAtUtc,
        boolean approved,
        String status,
        String rejectReason,
        Instant revokedAtUtc,
        String revokeReason
) {
}
