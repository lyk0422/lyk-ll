package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

import com.example.starter.hazmat.domain.RelayStatus;

/**
 * 押运接力段（换班记录）：计划跨越禁行起点的运单在接力点的双人换班。
 *
 * @param id              主键
 * @param shipmentId      运单 ID
 * @param approvalId      设置接力点时生效的路线核准记录 ID
 * @param waypointSeq     安全接力点途经点序号（计划通过时刻须严格早于禁行起点）
 * @param curfewStart     关联禁行起点时刻快照：换班完成时刻须严格早于该时刻，恰满即违约
 * @param status          接力段状态
 * @param outgoingEscort  交班押运员编号；未完成为 null
 * @param incomingEscort  接班押运员编号；未完成为 null
 * @param outgoingTime    交班打卡时刻；未打卡为 null
 * @param incomingTime    接班打卡时刻；未打卡为 null
 * @param completedAt     换班完成登记时刻；未完成为 null
 * @param createdAt       创建时刻
 */
public record RelaySegment(
        long id,
        long shipmentId,
        long approvalId,
        int waypointSeq,
        LocalDateTime curfewStart,
        RelayStatus status,
        String outgoingEscort,
        String incomingEscort,
        LocalDateTime outgoingTime,
        LocalDateTime incomingTime,
        LocalDateTime completedAt,
        LocalDateTime createdAt) {
}
