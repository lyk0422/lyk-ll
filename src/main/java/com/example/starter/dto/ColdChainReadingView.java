package com.example.starter.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 冷链温度读数视图。
 *
 * @param seq         片内读数序号
 * @param at          采样 UTC 精确时刻
 * @param temperature 温度（摄氏度）
 */
public record ColdChainReadingView(
        int seq,
        Instant at,
        BigDecimal temperature
) {
}
