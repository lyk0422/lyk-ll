package com.example.starter.domain;

/**
 * 审查状态：OPEN 待复核，RESOLVED 已复核（复核结论为最终口径）。
 */
public enum ReviewStatus {

    /** 待复核。 */
    OPEN,

    /** 已复核办结。 */
    RESOLVED
}
