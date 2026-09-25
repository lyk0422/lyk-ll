package com.example.starter.damage.domain;

/**
 * 残损案当前唯一判定：修理与全损不得并发成立。
 */
public enum Determination {
    /** 修理（单项修理费不超过箱值 30%，恰 30% 按修理处理） */
    REPAIR,
    /** 全损（任一单项修理费超过箱值 30%，或返修后复检仍不合格） */
    TOTAL_LOSS
}
