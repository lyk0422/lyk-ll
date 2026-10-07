package com.example.starter.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 冷链温度读数输入：片内递增序号 + 精确时刻 + 合成温度。
 *
 * @param seq         读数顺序号（片内从 1 起连续递增）
 * @param at          读数精确时刻（UTC，须落在片段闭区间内且不晚于提交时刻）
 * @param tempCelsius 合成温度读数（单位：摄氏度，合格闭区间 [2,8]，恰边界合格）
 */
public record ReadingInput(
        @NotNull @Positive Integer seq,
        @NotNull Instant at,
        @NotNull BigDecimal tempCelsius
) {
}
