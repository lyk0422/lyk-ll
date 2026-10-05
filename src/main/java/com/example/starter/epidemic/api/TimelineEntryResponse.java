package com.example.starter.epidemic.api;

import java.time.Instant;

/**
 * 聚集事件时间线条目：由事件、涉及卡片与协查单基础数据复算，重复查询稳定。
 *
 * @param time   发生时刻（UTC）
 * @param type   条目类型：EVENT_CREATED/CARD_ATTACHED/INVESTIGATION_CREATED/INVESTIGATION_CONCLUDED/EVENT_CLOSED
 * @param detail 条目说明（含卡号、协查单号等）
 */
public record TimelineEntryResponse(
        Instant time,
        String type,
        String detail
) {
}
