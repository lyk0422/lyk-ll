package com.example.starter.schedule.model;

/**
 * 编排条目状态。
 */
public enum EntryStatus {

    /** 已编排（当前版本内有效占位）。 */
    SCHEDULED,

    /** 被更高优先级申请挤占，携带重排建议。 */
    PREEMPTED,

    /** 已撤销（仅未冻结版本允许）。 */
    CANCELLED,

    /** 窗口结束时刻已过仍未收到回报，自动漏报终态。 */
    MISSED
}
