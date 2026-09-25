package com.example.starter.waterlogging.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 液位复测数据 water_level_reading 映射。
 */
public record WaterLevelReading(
        Long id,
        String stationCode,
        LocalDateTime readingTime,
        BigDecimal level,
        Long planId,
        LocalDateTime recordedAt) {
}
