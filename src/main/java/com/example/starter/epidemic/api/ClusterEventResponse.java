package com.example.starter.epidemic.api;

import com.example.starter.epidemic.domain.ClusterStatus;

import java.time.Instant;
import java.util.List;

/**
 * 聚集事件详情。
 *
 * @param eventNo     聚集事件编号
 * @param siteCode    暴露场所编码
 * @param siteName    暴露场所名称
 * @param status      事件状态 OPEN/CONFIRMED/EXCLUDED/CLOSED
 * @param firstCaseAt 首例报告时刻（UTC）
 * @param lastCaseAt  末例报告时刻（UTC）
 * @param cardCount   涉及报告卡数量
 * @param cardNos     涉及卡号（按归入顺序）
 * @param createdAt   事件生成时刻（UTC）
 * @param closedAt    关闭时刻（UTC），未关闭为 null
 */
public record ClusterEventResponse(
        String eventNo,
        String siteCode,
        String siteName,
        ClusterStatus status,
        Instant firstCaseAt,
        Instant lastCaseAt,
        int cardCount,
        List<String> cardNos,
        Instant createdAt,
        Instant closedAt
) {
}
