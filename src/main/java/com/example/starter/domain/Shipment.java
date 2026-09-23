package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 冷链货件。
 *
 * @param shipmentId                  货件唯一标识
 * @param minTempC                    允许温度闭区间下限（摄氏度，含端点）
 * @param maxTempC                    允许温度闭区间上限（摄氏度，含端点）
 * @param maxExcursionMinutes         最大连续超限分钟数（连续超限严格大于该值判 EXCURSION）
 * @param maxSampleIntervalMinutes    相邻读数最大采样间隔分钟数（严格大于判 INCOMPLETE）
 * @param version                     当前保管版本号（已完成交接次数，也是当前打开段 version）
 * @param createdAt                   创建时间，Unix 毫秒时间戳（UTC）
 */
public record Shipment(
        String shipmentId,
        BigDecimal minTempC,
        BigDecimal maxTempC,
        int maxExcursionMinutes,
        int maxSampleIntervalMinutes,
        int version,
        long createdAt
) {
}
