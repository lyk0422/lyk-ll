package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

import com.example.starter.hazmat.domain.EscortRole;

/**
 * 接力段单人打卡记录（去重键：运单+途经点序号+押运角色）。
 *
 * @param id          主键
 * @param relayId     接力段 ID
 * @param shipmentId  运单 ID
 * @param waypointSeq 接力点途经点序号
 * @param role        押运角色（交班/接班）
 * @param escortId    押运员编号（合成数据）
 * @param checkTime   打卡时刻（Asia/Shanghai）
 * @param requestId   幂等键
 * @param createdAt   记录创建时刻
 */
public record RelayCheckin(
        long id,
        long relayId,
        long shipmentId,
        int waypointSeq,
        EscortRole role,
        String escortId,
        LocalDateTime checkTime,
        String requestId,
        LocalDateTime createdAt) {
}
