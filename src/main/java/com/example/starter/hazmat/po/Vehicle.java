package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 车辆（合成基础数据）。
 *
 * @param plate            车牌号
 * @param frozen           是否因失联被冻结（冻结期间拒绝新运单申请）
 * @param frozenShipmentId 导致冻结的运单 ID；未冻结为 null
 * @param frozenAt         冻结时刻；未冻结为 null
 */
public record Vehicle(
        String plate,
        boolean frozen,
        Long frozenShipmentId,
        LocalDateTime frozenAt) {
}
