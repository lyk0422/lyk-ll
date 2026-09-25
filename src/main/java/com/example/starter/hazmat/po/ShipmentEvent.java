package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 运单事件：逆序告警、偏离预警、失联、结案后迟到打卡等。
 *
 * @param id         主键
 * @param shipmentId 运单 ID
 * @param eventType  事件类型
 * @param detail     事件详情（可复算依据）
 * @param eventTime  事件发生时刻
 */
public record ShipmentEvent(long id, long shipmentId, String eventType, String detail, LocalDateTime eventTime) {
}
