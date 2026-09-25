package com.example.starter.epidemic.domain;

/**
 * 时限判定结果。提交时刻恰在时限终点视为及时。
 */
public enum Timeliness {
    TIMELY,
    LATE
}
