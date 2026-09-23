package com.example.starter.coldchain.entity;

import java.time.LocalDateTime;

/**
 * 货件持久化对象。
 *
 * @param shipmentId                货件唯一标识
 * @param minTemp                   允许温度闭区间下限（摄氏度，含端点）
 * @param maxTemp                   允许温度闭区间上限（摄氏度，含端点）
 * @param maxExcursionMinutes       允许的最大连续超限分钟数
 * @param maxSampleIntervalSeconds  相邻读数最大采样间隔（秒）
 * @param currentVersion            当前开放运输段版本号
 * @param createdAt                 建件时间（Asia/Shanghai）
 */
public record Shipment(
        String shipmentId,
        double minTemp,
        double maxTemp,
        int maxExcursionMinutes,
        int maxSampleIntervalSeconds,
        int currentVersion,
        LocalDateTime createdAt
) {
}
