package com.example.starter.marpol.domain;

/**
 * 缺陷项整改状态。
 */
public enum ItemStatus {
    /** 待整改：截止日（核查日+14 自然日，UTC）前可关闭。 */
    OPEN,
    /** 已按期整改关闭（恰第 14 日关闭仍有效）。 */
    RECTIFIED,
    /** 逾期未整改关闭。 */
    OVERDUE
}
