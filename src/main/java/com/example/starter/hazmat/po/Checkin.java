package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 押运打卡记录。去重键：运单 + 路线版本 + 途经点序号（业务口径"运单＋途经点序号"，
 * 紧急改线后新生效版本拥有独立序号空间，历史版本打卡保留）。
 *
 * @param id         主键
 * @param shipmentId 运单 ID
 * @param approvalId 打卡时生效的路线版本
 * @param seq        途经点序号
 * @param checkTime  实际打卡时刻
 * @param requestId  打卡幂等键
 * @param createdAt  入库时刻
 */
public record Checkin(
        long id,
        long shipmentId,
        long approvalId,
        int seq,
        LocalDateTime checkTime,
        String requestId,
        LocalDateTime createdAt) {
}
