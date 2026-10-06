package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 接力段打卡记录。去重键：运单 + 途经点序号 + 押运员编号
 * （业务口径"运单＋途经点序号去重"，同一押运员在同一途经点仅生效一次；
 * 交班与接班为不同押运员，各自一行共同构成一次换班）。
 *
 * @param id          主键
 * @param relayId     所属接力段 ID
 * @param shipmentId  运单 ID
 * @param waypointSeq 接力点途经点序号
 * @param escortNo    打卡押运员编号
 * @param checkTime   打卡时刻，Asia/Shanghai
 * @param requestId   打卡幂等键
 * @param createdAt   入库时刻
 */
public record RelayCheckin(
        long id,
        long relayId,
        long shipmentId,
        int waypointSeq,
        String escortNo,
        LocalDateTime checkTime,
        String requestId,
        LocalDateTime createdAt) {
}
