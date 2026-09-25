package com.example.starter.gas.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 计量读数提交响应。
 *
 * @param duplicated 是否命中“同站同表底时刻”去重：true 表示读数已存在，本次未新写入
 */
public record ReadingSubmitResponse(
        String requestId,
        Long readingId,
        String stationCode,
        Instant meterTime,
        LocalDate gasDate,
        BigDecimal volume,
        BigDecimal heatingValue,
        boolean duplicated) {
}
