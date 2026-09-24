package com.example.starter.ili;

/**
 * 匹配对复核状态。仅增长、严重缺陷需要复核。
 */
public enum ReviewStatus {
    /** 稳定缺陷，无需复核。 */
    NOT_REQUIRED,
    /** 待复核。 */
    PENDING,
    /** 复核确认原分级。 */
    CONFIRMED,
    /** 复核降级到较低等级。 */
    DOWNGRADED
}
