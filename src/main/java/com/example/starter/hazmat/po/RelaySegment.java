package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

import com.example.starter.hazmat.domain.RelayStatus;

/**
 * 夜间接力押运段：计划到达夜间禁行起点前的运单须设置的安全接力点。
 * 换班记录含交班/接班双方编号与各自打卡时刻，双方均打卡才生效（单人打卡不生效）。
 *
 * @param id             主键
 * @param shipmentId     运单 ID
 * @param approvalId     设置接力段时生效的路线版本
 * @param waypointSeq    安全接力点途经点序号（计划通过时刻须早于禁行起点）
 * @param status         接力段状态
 * @param outgoingEscort 交班押运员编号
 * @param incomingEscort 接班押运员编号
 * @param deadline       换班截止时刻=夜间禁行起点，恰满即冻结
 * @param outgoingTime   交班押运员打卡时刻；未打卡为 null
 * @param incomingTime   接班押运员打卡时刻；未打卡为 null
 * @param completedAt    换班生效时刻；未完成为 null
 * @param requestId      创建接力段幂等键
 * @param createdAt      入库时刻
 */
public record RelaySegment(
        long id,
        long shipmentId,
        long approvalId,
        int waypointSeq,
        RelayStatus status,
        String outgoingEscort,
        String incomingEscort,
        LocalDateTime deadline,
        LocalDateTime outgoingTime,
        LocalDateTime incomingTime,
        LocalDateTime completedAt,
        String requestId,
        LocalDateTime createdAt) {
}
