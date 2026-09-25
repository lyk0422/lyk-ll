package com.example.starter.weight.domain;

/**
 * 超限分档口径。
 */
public enum DecisionTier {
    /**
     * 实测总重未超过限值（超限率恰为 0 或为负）：不超限，直接放行。
     */
    NO_OVERLOAD,
    /**
     * 超限率大于 0 且不足 30%：责令卸载放行。
     */
    UNLOAD_RELEASE,
    /**
     * 超限率达到 30% 及以上（恰 30% 落入本档）：罚款并记分。
     */
    FINE_POINTS
}
