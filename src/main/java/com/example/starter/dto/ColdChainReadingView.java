package com.example.starter.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 冷链温度读数视图（按版本冻结的证据原样展示）。
 *
 * @param seq         读数顺序号
 * @param at          读数精确时刻（UTC）
 * @param tempCelsius 合成温度读数（单位：摄氏度）
 */
public record ColdChainReadingView(int seq, Instant at, BigDecimal tempCelsius) {
}
