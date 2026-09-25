package com.example.starter.slot.domain;

/**
 * 系列申请的占用状态。
 */
public enum SeriesStatus {

    /** 时刻当前处于占用中（保留或公共池分配）。 */
    HELD,

    /** 系列已被退回，占用时刻已释放回公共池。 */
    RELEASED
}
