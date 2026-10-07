package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

import com.example.starter.hazmat.domain.RelaySegmentStatus;

/**
 * 押运接力段：运单在安全接力点的换班记录，含交接班双方编号与时刻。
 *
 * @param id                  主键
 * @param shipmentId          运单 ID
 * @param approvalId          设置接力点时生效的路线版本
 * @param relaySeq            安全接力点途经点序号
 * @param status              接力段状态
 * @param personnelControlled 是否启用人员管控：true 接班 IN 须先完成资格预约；false 保持旧行为
 * @param outEscortId         交班押运员编号；未完成为 null
 * @param outTime             交班打卡时刻；未完成为 null
 * @param inEscortId          接班押运员编号；未完成为 null
 * @param inTime              接班打卡时刻；未完成为 null
 * @param createdAt           创建时刻
 * @param completedAt         换班完成时刻（=接班打卡时刻）；未完成为 null
 */
public record RelaySegment(
        long id,
        long shipmentId,
        long approvalId,
        int relaySeq,
        RelaySegmentStatus status,
        boolean personnelControlled,
        String outEscortId,
        LocalDateTime outTime,
        String inEscortId,
        LocalDateTime inTime,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {
}
