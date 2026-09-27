package com.example.starter.pump.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 分区潮位查询结果。
 *
 * @param zoneCode 流域分区代码
 * @param readings 按采集时刻排序的潮位数据
 */
public record TideReadingListResult(
        String zoneCode,
        List<TideReadingItem> readings
) {

    /** 潮位单点。 */
    public record TideReadingItem(
            Long readingId,
            LocalDateTime readingTime,
            BigDecimal level,
            boolean late,
            LocalDateTime receivedAt
    ) {
    }
}
