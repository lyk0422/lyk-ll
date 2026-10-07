package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 冷链运输片段视图（按版本冻结的证据原样展示）。
 *
 * @param seq      片段顺序号
 * @param start    片段起点 UTC 精确时刻
 * @param end      片段终点 UTC 精确时刻
 * @param readings 本片段温度读数（按序号排序）
 */
public record ColdChainSegmentView(int seq, Instant start, Instant end,
                                   List<ColdChainReadingView> readings) {
}
