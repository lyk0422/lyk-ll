package com.example.starter.epidemic.api;

import com.example.starter.epidemic.domain.InvestigationStatus;

import java.time.Instant;

/**
 * 协查单响应。
 *
 * @param id              协查单编号
 * @param eventId         聚集事件编号
 * @param institutionCode 协查推送机构编码
 * @param status          IN_PROGRESS 进行中 / CONCLUDED 已办结
 * @param note            协查说明
 * @param conclusion      协查结论；未办结为 null
 * @param createdAt       创建时刻（UTC）
 * @param concludedAt     办结时刻（UTC）；未办结为 null
 */
public record InvestigationResponse(
        long id,
        long eventId,
        String institutionCode,
        InvestigationStatus status,
        String note,
        String conclusion,
        Instant createdAt,
        Instant concludedAt) {
}
