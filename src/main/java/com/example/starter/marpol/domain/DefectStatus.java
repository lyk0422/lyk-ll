package com.example.starter.marpol.domain;

/**
 * 缺陷整改状态。
 */
public enum DefectStatus {
    /** 待整改。 */
    OPEN,
    /** 已在期限内（含第 14 个自然日）关闭。 */
    CLOSED,
    /** 逾期未关闭。 */
    OVERDUE
}
