package com.example.starter.epidemic.api;

import com.example.starter.epidemic.domain.ClusterStatus;

import java.time.Instant;
import java.util.List;

/**
 * 聚集事件详情响应：含首末例时刻、涉及卡号时间线与协查结论回写。
 *
 * @param id                   聚集事件编号
 * @param siteCode             暴露场所编码
 * @param status               事件状态：OPEN/CONFIRMED/EXCLUDED/CLOSED(终态)
 * @param firstCaseAt          窗口内首例报告时刻（UTC）
 * @param lastCaseAt           窗口内末例报告时刻（UTC）
 * @param cards                涉及卡号时间线（含迟到卡），按报告时刻与主键稳定排序
 * @param conclusion           协查结论回写；未回写为 null
 * @param openInvestigationId  当前进行中协查单编号；无则为 null
 * @param createdAt            事件生成时刻（UTC）
 * @param closedAt             关闭时刻（UTC）；未关闭为 null
 */
public record ClusterEventResponse(
        long id,
        String siteCode,
        ClusterStatus status,
        Instant firstCaseAt,
        Instant lastCaseAt,
        List<ClusterEventCardView> cards,
        String conclusion,
        Long openInvestigationId,
        Instant createdAt,
        Instant closedAt) {
}
