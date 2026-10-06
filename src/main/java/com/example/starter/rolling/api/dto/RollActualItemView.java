package com.example.starter.rolling.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 轧辊实绩明细视图：列表按实绩时刻升序归位（乱序回传不影响查询顺序）。
 *
 * @param rollingKm  实绩里程（公里）
 * @param recordedAt 实绩发生时刻
 * @param firstUse   是否新辊上机后前 5 卷的首用标记
 * @param requestId  首次回传的幂等键
 */
public record RollActualItemView(
        BigDecimal rollingKm,
        LocalDateTime recordedAt,
        boolean firstUse,
        String requestId) {
}
