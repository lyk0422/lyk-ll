package com.example.starter.pump.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 潮位数据。id 即固定潮位版本。
 *
 * @param id         潮位版本ID（自增）
 * @param zoneCode   流域分区代码
 * @param tideTime   潮位采集UTC时刻（带时区）
 * @param tideLevel  潮位（米，非负）
 * @param receivedAt 服务接收时刻（Asia/Shanghai）
 */
public record TideReading(
        Long id,
        String zoneCode,
        OffsetDateTime tideTime,
        BigDecimal tideLevel,
        java.time.LocalDateTime receivedAt
) {
}
