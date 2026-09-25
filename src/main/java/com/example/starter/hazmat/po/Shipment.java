package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

import com.example.starter.hazmat.domain.ShipmentStatus;

/**
 * 危化品运单主表 PO。
 *
 * @param id                 运单主键
 * @param shipmentNo         运单号（业务唯一）
 * @param vehiclePlate       车牌号
 * @param goodsCategory      危化品类别编码
 * @param status             运单状态
 * @param currentApprovalId  当前生效路线核准记录 ID
 * @param plannedDeparture   计划发运时刻
 * @param createdAt          创建时刻
 */
public record Shipment(
        long id,
        String shipmentNo,
        String vehiclePlate,
        String goodsCategory,
        ShipmentStatus status,
        Long currentApprovalId,
        LocalDateTime plannedDeparture,
        LocalDateTime createdAt) {
}
