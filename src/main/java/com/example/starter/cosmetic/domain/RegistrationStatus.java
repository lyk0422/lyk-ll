package com.example.starter.cosmetic.domain;

/**
 * 备案状态机：公示中 → 异议审查 / 生效 → 被替代 / 注销；审查不通过与注销为终态。
 */
public enum RegistrationStatus {
    /** 公示中（形式审查已通过，公示期10个自然日）。 */
    PUBLICITY,
    /** 异议审查（公示期内收到异议）。 */
    OBJECTION_REVIEW,
    /** 已生效。 */
    EFFECTIVE,
    /** 审查不通过（终态）。 */
    REJECTED,
    /** 已被新版本替代（终态）。 */
    SUPERSEDED,
    /** 已注销（终态，重复注销幂等成功）。 */
    CANCELLED
}
