package com.example.starter.cosmetic.domain;

/**
 * 公示异议状态。
 */
public enum ObjectionStatus {
    /** 待审查。 */
    PENDING,
    /** 异议不成立，备案恢复流程。 */
    DISMISSED,
    /** 异议成立，备案不予生效。 */
    UPHELD
}
