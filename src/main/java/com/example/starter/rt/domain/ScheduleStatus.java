package com.example.starter.rt.domain;

/**
 * 分次设备排程状态。
 * 发布即占用（BOOKED）；治疗记录受控执行后释放占用并冻结实绩（EXECUTED）；
 * 手动取消或计划改版换版导致未开始排程释放（CANCELLED）。
 */
public enum ScheduleStatus {

    /** 已发布：设备时长窗口与分次均被占用，尚未执行。 */
    BOOKED,
    /** 已执行：受控治疗记录已在窗口内完成，设备占用已释放，实绩冻结不可更改。 */
    EXECUTED,
    /** 已取消：手动取消或计划改版触发，设备与分次占用均已释放，可重新排程。 */
    CANCELLED
}
