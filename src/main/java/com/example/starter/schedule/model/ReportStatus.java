package com.example.starter.schedule.model;

/**
 * 执行回报状态，提交后即终态，不可修改。
 */
public enum ReportStatus {

    /** 成功。 */
    SUCCESS,

    /** 失败。 */
    FAILURE,

    /** 部分成功。 */
    PARTIAL
}
