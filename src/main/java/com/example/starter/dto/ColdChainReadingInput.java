package com.example.starter.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 冷链温度读数输入。片内序号递增，时刻须落在片段闭区间内且不晚于提交时刻。
 *
 * @param seq         片内读数序号（递增，必填，缺序号不得通过）
 * @param at          读数采样 UTC 精确时刻
 * @param temperature 温度（摄氏度）；合成合格闭区间 [2,8]，恰边界合格
 */
public record ColdChainReadingInput(
        @NotNull Integer seq,
        @NotNull Instant at,
        @NotNull BigDecimal temperature
) {
}
