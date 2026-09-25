package com.example.starter.water.dto;

import java.math.BigDecimal;

/**
 * 读数上报结果；duplicate=true 表示同“计量点＋表底时刻”重复上报，未再次累计。
 *
 * @param delta      本次读数相对上一表底（按表底时刻排序）的增量（m³），归入 month 对应 UTC 自然月
 * @param duplicate  是否为重复上报
 */
public record ReadingResult(
        Long meterPointId,
        String readingTime,
        String month,
        BigDecimal cumulative,
        BigDecimal delta,
        boolean duplicate) {
}
