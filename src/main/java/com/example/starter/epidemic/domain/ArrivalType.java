package com.example.starter.epidemic.domain;

/**
 * 报告卡归入聚集事件的归并类型。
 */
public enum ArrivalType {
    /** 窗口内正常归并：计入事件首末例与触发计数。 */
    NORMAL,
    /** 事件关闭后迟到：只记迟到事件，可另行归并成新事件。 */
    LATE
}
