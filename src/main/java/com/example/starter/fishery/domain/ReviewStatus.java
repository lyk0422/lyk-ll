package com.example.starter.fishery.domain;

/**
 * 复核案件状态。
 */
public enum ReviewStatus {
    /** 待人工复核（审查队列）。 */
    OPEN,
    /** 复核结论已作出，为最终口径。 */
    CLOSED
}
