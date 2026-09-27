package com.example.starter.domain;

/**
 * 维保履约尝试状态。
 */
public enum AttemptStatus {
    /** 进行中：已占用两名人员与作业窗 */
    DISPATCHED,
    /** 救援抢占挂起：人员已释放，已做清单保留，义务不变 */
    SUSPENDED,
    /** 完成：全部清单完成且双人共同确认，已登记有效维保记录 */
    COMPLETED,
    /** 被取代：手工维保登记结清义务时关闭进行中的尝试 */
    SUPERSEDED,
    /** 挂起后迟到回报关闭：仅记迟到事件，不算本周期维保完成 */
    LATE_CLOSED
}
