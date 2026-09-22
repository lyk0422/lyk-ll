package com.example.starter.sensor.po;

/**
 * 时钟漂移校正参数版本行。
 *
 * @param id          记录 ID
 * @param sensor      传感器类型
 * @param version     版本号（按传感器自增）
 * @param slope       漂移斜率
 * @param offsetNanos 固定偏移，纳秒
 * @param effectiveAt 生效时刻，epoch 纳秒（事件时间）
 * @param createdAt   热更新写入墙钟时刻，epoch 毫秒
 */
public record CorrectionRow(
        long id,
        String sensor,
        long version,
        double slope,
        long offsetNanos,
        long effectiveAt,
        long createdAt) {
}
