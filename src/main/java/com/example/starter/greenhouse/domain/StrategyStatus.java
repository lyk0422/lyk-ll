package com.example.starter.greenhouse.domain;

/**
 * 气候策略生命周期状态。
 * DRAFT 编制 → VALIDATED 校验通过 → ACTIVE 生效中 → SUPERSEDED 被新策略替代。
 */
public enum StrategyStatus {
    DRAFT,
    VALIDATED,
    ACTIVE,
    SUPERSEDED
}
