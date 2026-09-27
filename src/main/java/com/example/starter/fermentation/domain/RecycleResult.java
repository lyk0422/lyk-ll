package com.example.starter.fermentation.domain;

/**
 * 酵母回收结果：合格转可用（代次加 1）或报废（终态）。
 */
public enum RecycleResult {

    /** 活率 ≥ 90% 且无污染：转可用，代次加 1。 */
    RETURNED,
    /** 活率不足 90% 或有污染：报废，不可再投酵。 */
    SCRAPPED
}
