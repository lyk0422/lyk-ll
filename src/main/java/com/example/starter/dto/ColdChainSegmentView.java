package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 冷链运输片段视图（含当前版本已提交读数；缺样本的片段读数为空列表，不以零度填充）。
 *
 * @param seq      片段序号
 * @param startUtc 片段起点 UTC 精确时刻（含）
 * @param endUtc   片段终点 UTC 精确时刻（区间不含）
 * @param readings 当前版本该片段已提交的温度读数
 */
public record ColdChainSegmentView(
        int seq,
        Instant startUtc,
        Instant endUtc,
        List<ColdChainReadingView> readings
) {
}
