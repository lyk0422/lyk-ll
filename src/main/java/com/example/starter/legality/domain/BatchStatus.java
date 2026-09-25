package com.example.starter.legality.domain;

/**
 * 排班校验批次状态。
 */
public enum BatchStatus {

    /** 已校验，结论可被后续补交延误触发的重判更新，可重复校验。 */
    CHECKED,

    /** 已封板，结论终态；之后补交的延误仅登记为迟到事件。 */
    FINALIZED
}
