package com.example.starter.spectrum.domain;

/**
 * 租约模式。
 */
public enum LeaseMode {

    /** 独占：与任一相遇租约冲突。 */
    EXCLUSIVE,

    /** 共享：仅在片段功率总和超限时冲突。 */
    SHARED
}
