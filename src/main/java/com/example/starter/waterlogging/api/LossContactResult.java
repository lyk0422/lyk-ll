package com.example.starter.waterlogging.api;

import java.time.LocalDateTime;

/**
 * 失联判定结果。
 *
 * @param lostContact 是否恰满 30 分钟无数据并判定失联
 * @param orderId     本次/既有开放派单主键，未失联时为空
 */
public record LossContactResult(
        String stationCode,
        LocalDateTime lastReadingTime,
        LocalDateTime checkTime,
        boolean lostContact,
        Long orderId,
        LocalDateTime declaredAt) {
}
