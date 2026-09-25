package com.example.starter.battery;

/**
 * 电池流转事件（哈希链节点）。
 *
 * @param id 主键
 * @param batteryCode 电池编码
 * @param seq 事件序号（从 1 开始单调递增）
 * @param eventType 事件类型
 * @param payload 事件业务内容（JSON）
 * @param prevHash 上一事件哈希，创世事件为 64 个 0
 * @param hash 本事件哈希（SHA-256 十六进制）
 * @param createdAt 事件时间（UTC epoch 毫秒）
 */
public record BatteryEvent(
        long id,
        String batteryCode,
        long seq,
        EventType eventType,
        String payload,
        String prevHash,
        String hash,
        long createdAt) {
}
