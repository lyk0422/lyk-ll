package com.example.starter.domain;

/**
 * 法律保全单状态。
 */
public enum HoldStatus {
    /** 活动：保全中的证据禁止清除。 */
    ACTIVE,
    /** 已解除：保全不再阻止清除。 */
    RELEASED
}
