package com.example.starter.waterlogging.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 液位数据上报结果。
 *
 * @param accepted  是否新入库；false 表示站点+时刻重复，仅返回既有数据
 * @param late      是否为归档后迟到数据（只登记迟到事件）
 */
public record ReadingResult(
        Long readingId,
        String stationCode,
        LocalDateTime readingTime,
        BigDecimal level,
        boolean accepted,
        boolean late) {
}
