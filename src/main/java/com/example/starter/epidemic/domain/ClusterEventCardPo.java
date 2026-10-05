package com.example.starter.epidemic.domain;

import java.time.Instant;

/**
 * 聚集事件涉及卡号行模型：记录卡号、报告时刻与归并类型（正常/迟到）。
 */
public record ClusterEventCardPo(
        /** 自增主键。 */
        long id,
        /** 聚集事件编号。 */
        long eventId,
        /** 卡片编号。 */
        String cardNo,
        /** 报告时刻（UTC）。 */
        Instant reportedAt,
        /** 归并类型：NORMAL 窗口内正常归并 / LATE 事件关闭后迟到。 */
        ArrivalType arrival) {
}
