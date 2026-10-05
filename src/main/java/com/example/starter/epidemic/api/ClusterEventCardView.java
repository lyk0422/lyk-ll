package com.example.starter.epidemic.api;

import java.time.Instant;

/**
 * 聚集事件涉及卡号视图（时间线元素）。
 *
 * @param cardNo     卡片编号
 * @param reportedAt 报告时刻（UTC）
 * @param arrival    归并类型：NORMAL 窗口内正常归并 / LATE 事件关闭后迟到
 */
public record ClusterEventCardView(String cardNo, Instant reportedAt, String arrival) {
}
