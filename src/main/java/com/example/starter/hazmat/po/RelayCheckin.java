package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

import com.example.starter.hazmat.domain.EscortRole;

/**
 * 接力双人打卡记录。去重键：接力段 + 角色（每段交接班各一条）。
 *
 * @param id         主键
 * @param segmentId  所属接力段
 * @param shipmentId 运单 ID
 * @param escortId   押运员编号
 * @param role       交班/接班角色
 * @param checkTime  打卡时刻（Asia/Shanghai）
 * @param requestId  打卡幂等键
 * @param createdAt  入库时刻
 */
public record RelayCheckin(
        long id,
        long segmentId,
        long shipmentId,
        String escortId,
        EscortRole role,
        LocalDateTime checkTime,
        String requestId,
        LocalDateTime createdAt) {
}
