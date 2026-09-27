package com.example.starter.domain;

/**
 * 履约尝试状态。
 */
public enum AttemptStatus {
    /** 已派发，待开始 */
    SCHEDULED,
    /** 作业中 */
    IN_PROGRESS,
    /** 救援接报原子挂起（释放人员，保留已做清单），终态 */
    SUSPENDED,
    /** 完成：全部清单完成并双人共同确认，已登记有效维保记录，终态 */
    COMPLETED,
    /** 挂起后的迟到回报：只记迟到事件，不当本周期维保完成，终态 */
    LATE_REPORTED,
    /** 义务被手工登记结清时取消，终态 */
    CANCELLED
}
