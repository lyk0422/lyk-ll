package com.example.starter.fishery.domain;

/**
 * 人工复核最终口径。
 */
public enum ReviewConclusion {
    /** 以进港卸渔量为准。 */
    CONFIRM_LANDED,
    /** 以日志累计量为准。 */
    CONFIRM_LOGGED,
    /** 复核员给出调整后数量。 */
    ADJUSTED
}
