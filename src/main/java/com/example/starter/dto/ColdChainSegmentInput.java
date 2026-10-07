package com.example.starter.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 冷链运输片段输入。片段区间为 [startUtc,endUtc)，正长度，相邻片段端点完全相接。
 *
 * @param seq      片段序号（从 1 连续递增，必填）
 * @param startUtc 片段起点 UTC 精确时刻（含）
 * @param endUtc   片段终点 UTC 精确时刻（区间不含，端点读数可取）
 */
public record ColdChainSegmentInput(
        @NotNull Integer seq,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc
) {
}
