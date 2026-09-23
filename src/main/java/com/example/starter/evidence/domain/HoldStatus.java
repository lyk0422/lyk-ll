package com.example.starter.evidence.domain;

/**
 * 法律保全单状态。
 */
public enum HoldStatus {
    /** 生效中：覆盖的证据不可被清除标记。 */
    ACTIVE,
    /** 已解除：不再阻止清除。 */
    RELEASED
}
