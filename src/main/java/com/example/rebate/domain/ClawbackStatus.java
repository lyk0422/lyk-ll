package com.example.rebate.domain;

/**
 * 追缴明细状态。COLLECTING 追缴中（允许多笔部分到账）；COMPLETED 已收齐（恰收齐转完成，此后只读）。
 */
public enum ClawbackStatus {
    COLLECTING,
    COMPLETED
}
